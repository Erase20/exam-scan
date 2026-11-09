"""
试卷 OCR 识别服务（FastAPI）

能力：
  POST /ocr/page       单页识别：图文分区 + 逐块文字识别
  POST /ocr/document   整份文档识别：多页合并，输出结构化区块流
  POST /ocr/crop       仅做区域裁剪，返回图片区块 base64（前端可预览）
  GET  /health         健康检查

引擎：RapidOCR（PP-OCRv4 中英文模型，ONNX Runtime 推理，无需 GPU）
"""
from __future__ import annotations

import base64
import io
import json
import logging
import os
import time
from concurrent.futures import ThreadPoolExecutor
from typing import List, Optional

import cv2
import numpy as np
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from PIL import Image

from layout import Block, analyze_layout, detect_text_rows, get_ink_mask, reading_order
from blank import BLANK_TEXT, detect_blank_lines, insert_blanks
from ocr_engine import OcrEngine

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("ocr-service")

app = FastAPI(title="Exam Scan OCR Service", version="1.0.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

engine = OcrEngine()

# 输出存储目录（Spring Boot 侧读取）
OUTPUT_DIR = os.environ.get("OCR_OUTPUT_DIR", os.path.join(os.getcwd(), "output"))
os.makedirs(OUTPUT_DIR, exist_ok=True)


def _decode_image(raw: bytes) -> np.ndarray:
    arr = np.frombuffer(raw, dtype=np.uint8)
    img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    if img is None:
        raise HTTPException(status_code=400, detail="无法解码图像")
    return img


def _resize_if_needed(img: np.ndarray, max_side: int = 2400) -> np.ndarray:
    """超大图先等比缩小，控制识别耗时；A4@300dpi 约 2480x3508"""
    h, w = img.shape[:2]
    long_side = max(h, w)
    if long_side <= max_side:
        return img
    scale = max_side / long_side
    return cv2.resize(img, (int(w * scale), int(h * scale)), interpolation=cv2.INTER_AREA)


def _crop_b64(img: np.ndarray, x: int, y: int, w: int, h: int) -> str:
    pad = 2
    h_img, w_img = img.shape[:2]
    x1, y1 = max(0, x - pad), max(0, y - pad)
    x2, y2 = min(w_img, x + w + pad), min(h_img, y + h + pad)
    roi = img[y1:y2, x1:x2]
    ok, buf = cv2.imencode(".png", roi)
    if not ok:
        return ""
    return base64.b64encode(buf.tobytes()).decode()


def _pad(x: int, y: int, w: int, h: int, W: int, H: int, pad: int = 12):
    """区块外扩，避免紧贴边界的字被切掉半边导致识别失败"""
    x1, y1 = max(0, x - pad), max(0, y - pad)
    x2, y2 = min(W, x + w + pad), min(H, y + h + pad)
    return x1, y1, max(1, x2 - x1), max(1, y2 - y1)


def _strip_slices(roi: np.ndarray, max_h: int = 520) -> List[tuple]:
    """
    高区块按"墨迹行间隙"切成若干条带。
    RapidOCR 对过高的输入会明显漏行，切成条带后每条单独识别可大幅提升召回。
    """
    h = roi.shape[0]
    if h <= max_h:
        return [(0, h)]
    gray = cv2.cvtColor(roi, cv2.COLOR_BGR2GRAY) if roi.ndim == 3 else roi
    try:
        ink = get_ink_mask(gray)
    except Exception:  # noqa: BLE001
        return [(i, min(h, i + max_h)) for i in range(0, h, max_h)]
    proj = ink.sum(axis=1)
    empty = proj <= max(1.0, proj.max() * 0.02)

    cuts = [0]
    start = 0
    while h - start > max_h:
        target = start + max_h
        best = None
        lo = max(start + int(max_h * 0.5), int(target - max_h * 0.3))
        hi = min(h - int(max_h * 0.3), int(target + max_h * 0.3))
        for yy in range(lo, hi):
            if empty[yy]:
                d = abs(yy - target)
                if best is None or d < best[0]:
                    best = (d, yy)
        start = best[1] if best else target
        if start <= cuts[-1] + 8:  # 避免切点退化成极窄条带
            start = cuts[-1] + max_h
        cuts.append(min(h, start))
    cuts.append(h)
    out = [(cuts[i], cuts[i + 1]) for i in range(len(cuts) - 1) if cuts[i + 1] - cuts[i] > 4]
    return out or [(0, h)]


def _enhance(roi: np.ndarray, scale: float = 1.4):
    """增强：放大 + CLAHE 提对比，用于小字/浅色字的二次识别"""
    try:
        img = cv2.resize(roi, None, fx=scale, fy=scale, interpolation=cv2.INTER_CUBIC)
        lab = cv2.cvtColor(img, cv2.COLOR_BGR2LAB)
        ch_l, ch_a, ch_b = cv2.split(lab)
        ch_l = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8)).apply(ch_l)
        img = cv2.cvtColor(cv2.merge([ch_l, ch_a, ch_b]), cv2.COLOR_LAB2BGR)
        return img, scale
    except Exception:  # noqa: BLE001
        return None, 1.0


def _merge_lines(a: List[dict], b: List[dict]) -> List[dict]:
    """按行位置配对两次识别结果，保留内容更完整的一版（含另一版多出的行）"""
    if not a:
        return b
    if not b:
        return a
    out = list(a)
    used = [False] * len(out)
    for l2 in b:
        best, bd = None, None
        for i, l1 in enumerate(out):
            if used[i]:
                continue
            c1 = (l1["minY"] + l1["maxY"]) / 2
            c2 = (l2["minY"] + l2["maxY"]) / 2
            d = abs(c1 - c2)
            tol = max(18, (l1["maxY"] - l1["minY"]) * 0.9)
            if d <= tol and (bd is None or d < bd):
                best, bd = i, d
        if best is None:
            out.append(l2)
            continue
        s1 = float(out[best].get("score") or 0)
        s2 = float(l2.get("score") or 0)
        longer = len(l2["text"]) > len(out[best]["text"])
        # 增强版文字更全、且置信度不明显更低时才替换；否则保留更可信的一版，
        # 避免"放大重识别"带来的低置信度乱码盖掉正确的原始结果
        if (longer and s2 >= s1 - 0.05) or s2 > s1 + 0.08:
            out[best] = l2
        used[best] = True
    out.sort(key=lambda l: (l["minY"], l["minX"]))
    return out


def _recognize_best(roi: np.ndarray, force: bool = False) -> List[dict]:
    """
    识别一个条带：先用原图；结果可疑（空结果、行数过少、置信度偏低）时
    用增强图重试一次，两次结果按行择优合并。

    force=True 表示无论结果好坏都做一次增强识别（框选区域才用）：
    用户框选的区域通常很小，多花一点时间换更高准确率是值得的。
    """
    lines = engine.recognize(roi)
    if (not force) and lines:
        avg = sum(l["score"] for l in lines) / max(1, len(lines))
        # 结果已足够好：不重试
        if len(lines) >= 2 and avg >= 0.72:
            return lines
        if len(lines) >= 4 and avg >= 0.65:
            return lines
    enh, scale = _enhance(roi)
    if enh is None or enh.size == 0:
        return lines
    lines2 = engine.recognize(enh)
    if lines2 and scale != 1.0:
        for ln in lines2:
            ln["minX"] = int(ln["minX"] / scale)
            ln["maxX"] = int(ln["maxX"] / scale)
            ln["minY"] = int(ln["minY"] / scale)
            ln["maxY"] = int(ln["maxY"] / scale)
            ln["cy"] = int(ln["cy"] / scale)
            ln["cx"] = int(ln["cx"] / scale)
    return _merge_lines(lines, lines2)


class _PageCtx:
    """
    单页识别上下文：用粗网格记录"哪些区域已经识别过"，并缓存已识别的行。

    版面分析出来的 TEXT 区块与图片/表格区块常常互相重叠（尤其正文被误判为图片时），
    若各自独立识别，同一片像素会被 RapidOCR 跑好几遍，单页耗时就会翻好几倍。
    有了这层记账，已覆盖区域直接复用缓存结果，保证整页每个像素基本只识别一次。
    """
    STEP = 16

    def __init__(self, W: int, H: int):
        self.W, self.H = W, H
        self.gw = W // _PageCtx.STEP + 1
        self.gh = H // _PageCtx.STEP + 1
        self.covered = np.zeros((self.gh, self.gw), dtype=bool)
        self.lines: List[dict] = []
        self.claimed: List[dict] = []

    def _same_line(self, g: dict) -> bool:
        """是否与已认领的行处于同一位置（同一物理行被不同裁剪框重复识别）"""
        gh = max(1, g["ay2"] - g["ay1"])
        gw = max(1, g["ax2"] - g["ax1"])
        for t in self.claimed:
            if abs(g["ay1"] - t["ay1"]) > max(10, 0.6 * gh):
                continue
            ox = min(g["ax2"], t["ax2"]) - max(g["ax1"], t["ax1"])
            ow = min(gw, max(1, t["ax2"] - t["ax1"]))
            if ox > 0.5 * ow:
                return True
        return False

    def _box(self, x: int, y: int, w: int, h: int):
        s = _PageCtx.STEP
        x1 = max(0, min(self.gw - 1, x // s))
        y1 = max(0, min(self.gh - 1, y // s))
        x2 = max(x1 + 1, min(self.gw, (x + w) // s + 1))
        y2 = max(y1 + 1, min(self.gh, (y + h) // s + 1))
        return x1, y1, x2, y2

    def coverage(self, x: int, y: int, w: int, h: int) -> float:
        x1, y1, x2, y2 = self._box(x, y, w, h)
        return float(self.covered[y1:y2, x1:x2].mean())

    def mark(self, x: int, y: int, w: int, h: int) -> None:
        x1, y1, x2, y2 = self._box(x, y, w, h)
        self.covered[y1:y2, x1:x2] = True

    def add_lines(self, lines: List[dict], ox: int, oy: int) -> None:
        for ln in lines:
            g = dict(ln)
            g["ax1"] = ox + int(ln.get("minX", 0))
            g["ax2"] = ox + int(ln.get("maxX", 0))
            g["ay1"] = oy + int(ln.get("minY", 0))
            g["ay2"] = oy + int(ln.get("maxY", 0))
            self.lines.append(g)

    def lines_in(self, x: int, y: int, w: int, h: int, claim: bool = False) -> List[dict]:
        """
        取出落在该范围内的已识别行，换算成区块局部坐标（返回副本，不污染缓存）。

        claim=True 时标记这些行已被"认领"：由于区块之间常常重叠（正文块与
        图片块覆盖同一片区域），必须保证同一行只归入一个区块，否则题目文本会重复。
        """
        out = []
        for g in self.lines:
            if claim and g.get("_taken"):
                continue
            if not (x <= g["ax1"] <= x + w and y <= g["ay1"] <= y + h):
                continue
            if claim:
                # 同一行可能被不同裁剪框识别出多次：按位置判定重复，只认领一次
                if self._same_line(g):
                    g["_taken"] = True
                    continue
                g["_taken"] = True
                self.claimed.append(g)
            ln = dict(g)
            ln["minX"] = g["ax1"] - x
            ln["maxX"] = g["ax2"] - x
            ln["minY"] = g["ay1"] - y
            ln["maxY"] = g["ay2"] - y
            ln["cx"] = (ln["minX"] + ln["maxX"]) // 2
            ln["cy"] = (ln["minY"] + ln["maxY"]) // 2
            out.append(ln)
        return out


def _column_slices(roi: np.ndarray, max_w: int = 900) -> List[tuple]:
    """
    与 _strip_slices 同理，只是沿水平方向切：在"墨迹空列"处下刀。
    合并后的矩形常常接近整页宽，直接送检会因模型缩放而漏字，
    切成较窄的竖条再识别可以保住召回。
    """
    h, w = roi.shape[:2]
    if h == 0 or w == 0:
        return []
    if w <= max_w:
        return [(0, w)]
    gray = cv2.cvtColor(roi, cv2.COLOR_BGR2GRAY) if roi.ndim == 3 else roi
    try:
        ink = get_ink_mask(gray)
    except Exception:  # noqa: BLE001
        return [(i, min(w, i + max_w)) for i in range(0, w, max_w)]
    proj = ink.sum(axis=0)
    empty = proj <= max(1.0, proj.max() * 0.02)

    cuts = [0]
    start = 0
    while w - start > max_w:
        target = start + max_w
        best = None
        lo = max(start + int(max_w * 0.5), int(target - max_w * 0.3))
        hi = min(w - int(max_w * 0.3), int(target + max_w * 0.3))
        for xx in range(lo, hi):
            if empty[xx]:
                d = abs(xx - target)
                if best is None or d < best[0]:
                    best = (d, xx)
        start = best[1] if best else target
        if start <= cuts[-1] + 8:
            start = cuts[-1] + max_w
        cuts.append(min(w, start))
    cuts.append(w)
    out = [(cuts[i], cuts[i + 1]) for i in range(len(cuts) - 1) if cuts[i + 1] - cuts[i] > 8]
    return out or [(0, w)]


def _has_ink(roi: np.ndarray) -> bool:
    """切片里几乎没有墨迹时直接跳过 OCR（省掉大段空白goroutine的推理）"""
    gray = cv2.cvtColor(roi, cv2.COLOR_BGR2GRAY) if roi.ndim == 3 else roi
    try:
        ink = get_ink_mask(gray)
    except Exception:  # noqa: BLE001
        return True
    return float(np.count_nonzero(ink)) / float(max(1, gray.size)) > 0.0015


def _drop_nested_text_blocks(blocks) -> List[Block]:
    """
    去掉完全嵌在更大正文区块里的小正文区块（只用于跳过 OCR，不删除区块本身）。

    版面分析常常把一个段落同时切出"大块"和"几行的小块"，小块的范围完全落在大块内，
    对它们重复识别只是浪费时间；归属阶段仍会从大块的识别结果里给它们分配行。
    """
    texts = [b for b in blocks if b.block_type == "TEXT"]
    drop = set()
    for i, b in enumerate(texts):
        for j, o in enumerate(texts):
            if i == j or o.w * o.h <= b.w * b.h:
                continue
            ix = min(b.x + b.w, o.x + o.w) - max(b.x, o.x)
            iy = min(b.y + b.h, o.y + o.h) - max(b.y, o.y)
            if ix > 0 and iy > 0 and ix * iy > 0.9 * b.w * b.h:
                drop.add(id(b))
                break
    return [b for b in blocks if id(b) not in drop]


OCR_WORKERS = max(1, min(6, int(os.environ.get("OCR_WORKERS", 1))))


def _ocr_blocks(img: np.ndarray, blocks, ctx: _PageCtx) -> None:
    """
    识别所有区块（默认串行）。

    实测：RapidOCR 底层的 ONNX Runtime 自身已有线程池，这里再开多线程会互相争抢，
    wall time 反而更慢，因此默认串行；高并发机器可用环境变量 OCR_WORKERS 调整。
    """
    if len(blocks) <= 1 or OCR_WORKERS <= 1:
        for b in blocks:
            _ocr_block(img, b, ctx)
        return
    try:
        with ThreadPoolExecutor(max_workers=OCR_WORKERS) as pool:
            list(pool.map(lambda b: _ocr_block(img, b, ctx), blocks))
    except Exception:  # noqa: BLE001  （极少数环境下引擎非线程安全，回退串行）
        log.warning("并发识别失败，回退串行")
        for b in blocks:
            _ocr_block(img, b, ctx)


def _uncovered_bands(ctx: _PageCtx, x: int, y: int, w: int, h: int,
                     min_h: int = 28) -> List[tuple]:
    """
    求矩形内尚未被识别过的水平带（返回块内相对坐标 y1,y2）。

    区块之间经常重叠，若每个区块都整块重识别，同一片像素会被跑好几遍。
    这里按覆盖网格逐行检查，只把真正没识别过的部分交出去。
    """
    s = _PageCtx.STEP
    gy1 = max(0, y // s)
    gy2 = min(ctx.gh, (y + h) // s + 1)
    x1 = max(0, x // s)
    x2 = min(ctx.gw, (x + w) // s + 1)

    bands: List[tuple] = []
    start = None
    for gy in range(gy1, gy2):
        row = ctx.covered[gy, x1:x2]
        # 只有"几乎完全没识别过"的行才送去识别。
        # 若放宽到部分覆盖，一行字会被切成上下两截分别识别，反而识别不出来。
        uncover = row.size == 0 or (row.mean() < 0.05)
        if uncover and start is None:
            start = gy
        elif not uncover and start is not None:
            if (gy - start) * s >= min_h:
                bands.append((start, gy))
            start = None
    if start is not None and (gy2 - start) * s >= min_h:
        bands.append((start, gy2))
    out = []
    for (a, b) in bands:
        top = max(y, a * s) - y
        bot = min(y + h, b * s) - y
        if bot - top >= min_h:
            out.append((max(0, top), min(h, bot)))
    return out


def _sort_lines(lines: List[dict], gutter: int | None = None) -> List[dict]:
    """
    行排序：先按纵向聚成"行带"，带内再按横坐标排。

    直接按 (y, x) 排序会因为片段间的几像素抖动把同一行的文字拆散，
    导致题干读起来是乱的（题目切分也会跟着错），所以这里按容差先归并同一行。
    双栏/跨页时先按中缝分成左、右两组，各自成文，避免左右栏交错串题。
    """
    if len(lines) <= 1:
        return lines
    if gutter is not None:
        left, right = [], []
        for ln in lines:
            cx = (ln.get("absX", 0) + ln.get("absX2", ln.get("absX", 0))) / 2
            (left if cx < gutter else right).append(ln)
        if left and right:
            return _sort_lines(left) + _sort_lines(right)
    hs = [max(1, l.get("absY2", 0) - l.get("absY1", 0)) for l in lines]
    tol = max(8, int(np.median(hs) * 0.7)) if hs else 10
    ordered = sorted(lines, key=lambda l: (l["absY1"], l["absX"]))
    out: List[dict] = []
    band: List[dict] = [ordered[0]]
    band_y = ordered[0]["absY1"]
    for ln in ordered[1:]:
        if abs(ln["absY1"] - band_y) <= tol:
            band.append(ln)
        else:
            out.extend(sorted(band, key=lambda l: l["absX"]))
            band = [ln]
            band_y = ln["absY1"]
    out.extend(sorted(band, key=lambda l: l["absX"]))
    return out


def _merge_row_fragments(lines: List[dict]) -> List[dict]:
    """
    把同一物理行被拆开的片段合并成一行。

    宽区域会按墨迹空列切成竖条分别识别，同一行文字因此变成"左段/中段/右段"多条，
    直接输出会把题干打散（"有些" / "古生物已经灭绝" / ",科学家是通过"）。
    这里按行高容差判断是否同一行，按横坐标顺序拼回整行文本。
    """
    if len(lines) <= 1:
        return lines
    hs = [max(1, l.get("absY2", 0) - l.get("absY1", 0)) for l in lines]
    tol = max(10, int(np.median(hs) * 0.6))
    out: List[dict] = []
    for ln in lines:
        merged = False
        # 回看窗口给足 12 条：一行被切成好几段时，后段仍要能和首段拼回去，
        # 否则一行变成多条文本，切题时会多切出题目（表现为分题不准）
        for prev in reversed(out[-12:]):
            if abs(prev["absY1"] - ln["absY1"]) > tol:
                continue
            gap = ln["absX"] - prev["absX2"]
            # 间距过大说明是分栏的另一列，不能拼在一起；
            # 填空横线两侧的间距会比较大，这里放宽到能跨过一条空位
            if gap > max(160, 6 * tol):
                continue
            prev["text"] = (prev["text"] + (" " if gap > tol else "") + ln["text"]).strip()
            prev["absX2"] = max(prev["absX2"], ln["absX2"])
            prev["absY1"] = min(prev["absY1"], ln["absY1"])
            prev["absY2"] = max(prev["absY2"], ln["absY2"])
            prev["maxX"] = prev["absX2"]
            prev["minY"] = prev["absY1"]
            prev["maxY"] = prev["absY2"]
            try:
                prev["score"] = max(float(prev.get("score") or 0), float(ln.get("score") or 0))
            except Exception:  # noqa: BLE001
                pass
            merged = True
            break
        if not merged:
            out.append(ln)
    return out


def _fill_block(block, ctx: _PageCtx, claim: bool = False) -> None:
    """从共享结果里取出属于该区块的行，并回填内容、置信度、整页坐标"""
    x, y, w, h = _pad(block.x, block.y, block.w, block.h, ctx.W, ctx.H)
    lines = ctx.lines_in(x, y, w, h, claim=claim)
    for ln in lines:
        ln["absY"] = y + ln["cy"]
        ln["absX"] = x + ln["minX"]
        ln["absX2"] = x + ln["maxX"]
        ln["absY1"] = y + ln["minY"]
        ln["absY2"] = y + ln["maxY"]
    lines = _sort_lines(lines, getattr(ctx, "gutter", None))
    block.lines = lines
    block.content = "\n".join(l["text"] for l in lines)
    try:
        block.confidence = float(np.mean([l["score"] for l in lines])) if lines else 0.0
    except Exception:  # noqa: BLE001
        block.confidence = 0.0


def _apply_blanks(block, blanks: list | None) -> None:
    """把落在区块内的填空横线还原成「（　　）」（统一在全区识别完后调用一次，避免重复插入）"""
    if not blanks or getattr(block, "_blanked", False):
        return
    x, y, w, h = block.x, block.y, block.w, block.h
    local = [(bx - x, by - y, bw, bh) for (bx, by, bw, bh) in blanks
             if x <= bx and bx + bw <= x + w and y <= by and by + bh <= y + h]
    if not local or not block.lines:
        return
    insert_blanks(None, block.lines, local)
    block.content = "\n".join(l["text"] for l in block.lines)
    block._blanked = True


def _ocr_block(img: np.ndarray, block, ctx: _PageCtx | None = None) -> None:
    """
    识别单个区块（结果写入共享 ctx，由 _fill_block 统一归属）。

    要点：
    1. 保持"原区块尺寸"——合并成整页再识别会让检测模型把字缩得太小，明显漏行；
    2. 正文区块各自完整识别：RapidOCR 对同一片区域换一个裁剪框常常识别出不同的行，
       适度换框比"严格去重"能拿到更高的总召回（换来的是真正少丢内容）；
       重复内容在归属阶段由 claim 机制消除，不会体现在最终文本里；
    3. 图片/表格区块只在未被正文覆盖时才识别，避免明显浪费；
    4. 过宽的区块按墨迹空列切成竖条、过高的区块按墨迹空行切成横条，避免缩放导致漏字。
    """
    H, W = img.shape[:2]
    x, y, w, h = _pad(block.x, block.y, block.w, block.h, W, H)
    roi = img[y:y + h, x:x + w]
    if roi.size == 0:
        return

    allow_retry = True
    bands = [(0, h)]
    if ctx is not None:
        cov = ctx.coverage(x, y, w, h)
        if cov > 0.95:
            ctx.mark(x, y, w, h)
            return
        if block.block_type != "TEXT" and cov > 0.5:
            # 图片/表格区的主要部分已被正文区块识别过，不必再跑一遍
            ctx.mark(x, y, w, h)
            return
        # 说明：实测表明，同一片区域换一个裁剪框往往还能识别出不同的行，
        # 因此对正文区块仍做完整识别（多出来的行由归属阶段的位置去重消除），
        # 这是"少丢内容"最关键的一步。
        allow_retry = cov < 0.35
    lines: List[dict] = []
    for (by1, by2) in bands:
        band = roi[by1:by2, :]
        if band.size == 0:
            continue
        for (sx, ex) in _column_slices(band):
            col = band[:, sx:ex]
            if col.size == 0:
                continue
            for (sy, ey) in _strip_slices(col):
                sub = col[sy:ey, :]
                if sub.size == 0 or not _has_ink(sub):
                    continue
                for ln in (_recognize_best(sub) if allow_retry else engine.recognize(sub)):
                    ln["minX"] += sx
                    ln["maxX"] += sx
                    ln["cx"] += sx
                    ln["minY"] += by1 + sy
                    ln["maxY"] += by1 + sy
                    ln["cy"] += by1 + sy
                    lines.append(ln)
    if ctx is not None:
        ctx.mark(x, y, w, h)
        ctx.add_lines(lines, x, y)


def _rescue_misjudged_blocks(blocks) -> int:
    """
    兜底判定：非 TEXT 区块已经参与过识别（行已由共享结果回填），这里只做改判。
    只要识别出成段文字（>=2 行，或单行但内容较长）且置信度不太低，
    就改判为 TEXT——版面分析很容易把正文误判成图片/表格，宁可误收也不要丢内容。
    返回改判数量。
    """
    rescued = 0
    extras: List[Block] = []
    for b in blocks:
        if b.block_type == "TEXT":
            continue
        chars = sum(len(l.get("text") or "") for l in b.lines)
        enough = len(b.lines) >= 2 or chars >= 6
        if enough and b.confidence >= 0.5:
            b.block_type = "TEXT"
            rescued += 1
        elif len(b.lines) >= 2 and b.confidence >= 0.55:
            # 不足以改判为正文，但确实识别出成行文字（常见于图表内的说明、表格内的选项）。
            # 另建一个同范围的 TEXT 区块承载这些文字，原区块仍按图片/表格展示，避免内容丢失。
            extras.append(Block(b.x, b.y, b.w, b.h, "TEXT",
                                confidence=b.confidence, content=b.content,
                                lines=list(b.lines)))
    if extras:
        blocks.extend(extras)
        log.info("图片/表格区内文字：新增 %d 个承载区块", len(extras))
    return rescued


def _recover_missing_lines(img: np.ndarray, gray: np.ndarray,
                           blocks, ctx: _PageCtx | None = None) -> List[Block]:
    """
    补漏：找出没有被任何区块覆盖到的文字行候选，单独裁出来识别。
    版面分析一旦漏判（例如把正文判成了空白），这些内容就会被整行丢掉，
    本步骤是防止内容丢失的最后一道保险。
    """
    H, W = img.shape[:2]
    try:
        if gray.shape[:2] != (H, W):
            gray = cv2.resize(gray, (W, H))
        rows = detect_text_rows(get_ink_mask(gray), [])
    except Exception:  # noqa: BLE001
        return []
    if not rows:
        return []

    covered = [(b.x, b.y, b.x + b.w, b.y + b.h) for b in blocks]
    missed = []
    for (rx, ry, rw, rh) in rows:
        cx, cy = rx + rw / 2, ry + rh / 2
        if any(x1 <= cx <= x2 and y1 <= cy <= y2 for (x1, y1, x2, y2) in covered):
            continue
        missed.append((rx, ry, rw, rh))
    if not missed:
        return []

    # 未覆盖的行按"视觉行"聚成条带
    missed.sort(key=lambda r: (r[1], r[0]))
    groups: List[dict] = []
    for (rx, ry, rw, rh) in missed:
        placed = False
        for g in groups:
            v_touch = ry <= g["y2"] + rh * 0.6 and ry + rh >= g["y1"] - rh * 0.6
            h_near = not (rx > g["x2"] + 80 or rx + rw < g["x1"] - 80)
            if v_touch and h_near:
                g["x1"] = min(g["x1"], rx)
                g["x2"] = max(g["x2"], rx + rw)
                g["y1"] = min(g["y1"], ry)
                g["y2"] = max(g["y2"], ry + rh)
                placed = True
                break
        if not placed:
            groups.append({"x1": rx, "x2": rx + rw, "y1": ry, "y2": ry + rh})

    out: List[Block] = []
    for g in groups:
        b = Block(int(g["x1"]) - 6, int(g["y1"]) - 6,
                  int(g["x2"] - g["x1"]) + 12, int(g["y2"] - g["y1"]) + 12, "TEXT")
        _ocr_block(img, b, ctx)
        if b.content and b.content.strip():
            out.append(b)
    if out:
        log.info("补漏新增 %d 个文字区块 / %d 行", len(out), sum(len(b.lines) for b in out))
    return out


@app.get("/health")
def health():
    return {
        "status": "ok",
        "engine": engine.name,
        "ready": engine.ready,
        "outputDir": OUTPUT_DIR,
    }


@app.post("/ocr/page")
async def ocr_page(
    file: UploadFile = File(...),
    returnCrop: bool = Form(True),
    docId: Optional[str] = Form(None),
):
    """
    单页识别：返回图文分区 + 每块文字
    """
    t0 = time.time()
    raw = await file.read()
    img = _resize_if_needed(_decode_image(raw))

    blocks, gray, angle, img, gutter = analyze_layout(img)
    h, w = img.shape[:2]
    ctx = _PageCtx(w, h)
    ctx.gutter = gutter

    # 大块优先：先让覆盖整片区域的大区块完整识别（此时覆盖率低，允许增强重试），
    # 再让小块在其基础上补漏——实测这样拿到的总行数最多
    order = sorted(blocks, key=lambda b: -(b.w * b.h))
    _ocr_blocks(img, order, ctx)
    # 归属时 TEXT 区块优先、小块优先：重叠区域内的每一行只归入一个区块，避免内容重复
    for b in sorted(blocks, key=lambda b: (0 if b.block_type == "TEXT" else 1, b.w * b.h)):
        _fill_block(b, ctx, claim=True)
    text_cnt = sum(1 for b in blocks if b.block_type == "TEXT")
    rescued = _rescue_misjudged_blocks(blocks)
    # 补漏：把区块都没覆盖到的文字行再识别一遍（彩色图与灰度图已同步去倾斜，坐标一致）
    extra = _recover_missing_lines(img, gray, blocks)
    if extra:
        blocks = reading_order(blocks + extra, gutter)
    blanks = detect_blank_lines(gray)
    for b in blocks:
        if b.block_type == "TEXT":
            _apply_blanks(b, blanks)
    # 过滤识别不出文字的空 TEXT 块（通常是插图内的碎片行）
    blocks = [b for b in blocks if b.block_type != "TEXT" or b.content.strip()]

    result_blocks = []
    for b in blocks:
        d = b.to_dict()
        if returnCrop and b.block_type != "TEXT":
            d["imageBase64"] = _crop_b64(img, b.x, b.y, b.w, b.h)
        # TEXT 区块也携带行坐标，供前端高亮
        result_blocks.append(d)

    log.info("page parsed: %dx%d, blocks=%d, text=%d, %.0fms",
             w, h, len(blocks), text_cnt, (time.time() - t0) * 1000)

    return {
        "width": w,
        "height": h,
        "skewAngle": round(angle, 2),
        "costMs": int((time.time() - t0) * 1000),
        "imageBase64": _crop_b64(img, 0, 0, w, h) if returnCrop else None,
        "blocks": result_blocks,
        "stat": {
            "textBlocks": sum(1 for b in blocks if b.block_type == "TEXT"),
            "imageBlocks": sum(1 for b in blocks if b.block_type == "IMAGE"),
            "tableBlocks": sum(1 for b in blocks if b.block_type == "TABLE"),
        },
    }


@app.post("/ocr/document")
async def ocr_document(
    files: List[UploadFile] = File(...),
    returnCrop: bool = Form(True),
):
    """
    多页识别：按页返回区块，同时给出全文（供题目切分）
    """
    t0 = time.time()
    pages = []
    for idx, f in enumerate(files):
        raw = await f.read()
        img = _resize_if_needed(_decode_image(raw))
        blocks, gray, angle, img, gutter = analyze_layout(img)
        h, w = img.shape[:2]
        # 先整页检测填空横线，再在识别出的行里还原成左右括号
        # 注意：版面分析可能把整块正文误判为图片（之后由兜底逻辑改回 TEXT），
        # 因此这里不能按区块类型过滤横线
        blanks = detect_blank_lines(gray)
        ctx = _PageCtx(w, h)
        ctx.gutter = gutter
        # 完全嵌在大区块里的小区块不单独识别，避免纯浪费
        order = sorted(_drop_nested_text_blocks(blocks), key=lambda b: -(b.w * b.h))
        _ocr_blocks(img, order, ctx)
        # 归属时 TEXT 区块优先、小块优先：重叠区域内的每一行只归入一个区块，避免内容重复
        for b in sorted(blocks, key=lambda b: (0 if b.block_type == "TEXT" else 1, b.w * b.h)):
            _fill_block(b, ctx, claim=True)
        _rescue_misjudged_blocks(blocks)
        # 补漏：把区块都没覆盖到的文字行再识别一遍（彩色图与灰度图已同步去倾斜，坐标一致）
        extra = _recover_missing_lines(img, gray, blocks)
        if extra:
            blocks = reading_order(blocks + extra, gutter)
        # 填空横线统一在所有区块识别完成后还原一次，避免重复区域重复插入
        for b in blocks:
            if b.block_type == "TEXT":
                _apply_blanks(b, blanks)
        blocks = [b for b in blocks if b.block_type != "TEXT" or b.content.strip()]
        pblocks = []
        page_lines = []
        for b in blocks:
            d = b.to_dict()
            d["pageNo"] = idx + 1
            if returnCrop and b.block_type != "TEXT":
                d["imageBase64"] = _crop_b64(img, b.x, b.y, b.w, b.h)
            pblocks.append(d)
            if b.block_type == "TEXT":
                page_lines.extend(l for l in b.lines if "absX" in l and "absY1" in l)
        ordered_lines = _sort_lines(page_lines, gutter)
        pages.append({
            "pageNo": idx + 1,
            "width": w,
            "height": h,
            "skewAngle": round(angle, 2),
            "imageBase64": _crop_b64(img, 0, 0, w, h) if returnCrop else None,
            "blocks": pblocks,
            "fullText": "\n".join(l["text"] for l in ordered_lines if l.get("text")),
        })

    # 全文按"全局行顺序"生成，而不是逐区块拼接内容：
    # 版面分析常把同一栏正文拆成「一个大区块 + 几个小区块」，按区块拼接会让
    # 题干与它的选项、下一题交错（切题时表现为串题）。这里把所有已认领的行
    # 汇总后按栏→行带→横坐标统一排序，保证题目文本连续、顺序可读。
    full_text = "\n".join(p.get("fullText", "") for p in pages if p.get("fullText"))
    return {
        "pageCount": len(pages),
        "costMs": int((time.time() - t0) * 1000),
        "pages": pages,
        "fullText": full_text,
    }


def _recognize_area(img: np.ndarray, x: int, y: int, w: int, h: int) -> List[dict]:
    """
    高精度识别一个框选区域（返回区域局部坐标的行）。

    框选区域是用户明确指定的内容区，这里把力气花在"别漏字、别切碎"上：
      1. 外扩少量 padding，避免贴边的字被切掉半边；
      2. 过宽时整体等比缩小（而不是横向切列）——横向切列会把一行字从中间劈开，
         那一行的左右两半会变成两条独立文本，空位（____）也就插不回原位了；
      3. 高区域按墨迹空行切条带，每条单独识别（RapidOCR 对过高的输入会漏行）；
      4. 每个条带用 _recognize_best（必要时放大+增强重识别，按行择优合并），
         缩放造成的清晰度损失正好被增强重试补回来；
      5. 最后按"行带"聚类排序并合并同一行的碎片。
    """
    H, W = img.shape[:2]
    # 外扩 16px：用户框选往往刚好压着文字边，贴边的字被切掉半边会识别成完全不同的字
    px, py, pw, ph = _pad(x, y, w, h, W, H, pad=16)
    roi = img[py:py + ph, px:px + pw]
    if roi.size == 0:
        return []

    # 区域越小越舍得花时间：小区域（用户只框了一两道题）强制做增强重识别
    force = (roi.shape[0] * roi.shape[1]) < 800_000

    # 极扁的区域（只框住一两行）先上下补白，把宽高比拉回正常范围。
    # RapidOCR 的检测模型按"短边"缩放，一条 1400x140 的窄条会被放大到上万像素宽，
    # 检测直接失效、整块文字识别不出来——这正是"区域越小越漏题"的直接原因。
    offx = px - x
    offy = py - y
    padtop = 0
    padleft = 0
    hh, ww = roi.shape[:2]
    if ww > 0 and hh > 0 and ww / hh > 8:
        target_h = max(hh, min(ww // 6, hh * 8))
        pad = (target_h - hh) // 2
        if pad > 0:
            roi = cv2.copyMakeBorder(roi, pad, target_h - hh - pad, 0, 0,
                                     cv2.BORDER_CONSTANT, value=(255, 255, 255))
            padtop = pad
    # 极窄的区域（如只框住一列选项）同理左右补白
    hh, ww = roi.shape[:2]
    if ww > 0 and hh > 0 and hh / ww > 8:
        target_w = max(ww, min(hh // 6, ww * 8))
        pad = (target_w - ww) // 2
        if pad > 0:
            roi = cv2.copyMakeBorder(roi, 0, 0, pad, target_w - ww - pad,
                                     cv2.BORDER_CONSTANT, value=(255, 255, 255))
            padleft = pad

    # 1500 以内保持原尺寸：不缩放时识别最准，用户框选的题目区通常都在这个宽度内
    scale = 1.0
    if roi.shape[1] > 1500:
        scale = 1500.0 / roi.shape[1]
        roi = cv2.resize(roi, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)

    # 窄小区域再放大 2 倍：只框了一两道题时字号往往偏小，
    # 放大后检测和识别都更稳，坐标在出口处按 scale 统一还原
    if scale == 1.0 and roi.shape[1] < 800 and roi.shape[0] < 600:
        roi = cv2.resize(roi, None, fx=2.0, fy=2.0, interpolation=cv2.INTER_CUBIC)
        scale = 2.0

    lines: List[dict] = []
    for (sy, ey) in _strip_slices(roi, max_h=520):
        sub = roi[sy:ey, :]
        if sub.size == 0 or not _has_ink(sub):
            continue
        top = int(sy / scale) - padtop
        for ln in _recognize_best(sub, force=force):
            ln["minX"] = int(ln["minX"] / scale) - offx - padleft
            ln["maxX"] = int(ln["maxX"] / scale) - offx - padleft
            ln["cx"] = int(ln["cx"] / scale) - offx - padleft
            ln["minY"] = int(ln["minY"] / scale) + top - offy
            ln["maxY"] = int(ln["maxY"] / scale) + top - offy
            ln["cy"] = int(ln["cy"] / scale) + top - offy
            lines.append(ln)

    for ln in lines:
        ln["absX"] = x + ln["minX"]
        ln["absX2"] = x + ln["maxX"]
        ln["absY"] = y + ln["cy"]
        ln["absY1"] = y + ln["minY"]
        ln["absY2"] = y + ln["maxY"]
    lines = _merge_row_fragments(_sort_lines(lines))

    # 外扩是为了让贴边的字完整，但框外那一行往往只露出半截、会变成一串乱码。
    # 这里只保留至少 70% 落在用户框内的行——单行区域同样要过滤，
    # 否则邻题被外扩带进来的半行会混进本题，表现就是"分题不准"。
    keep = []
    for l in lines:
        top, bot = l["absY1"], l["absY2"]
        ov = min(bot, y + h) - max(top, y)
        if ov >= 0.7 * max(1, bot - top):
            keep.append(l)
    return keep if keep else lines


@app.post("/ocr/regions")
async def ocr_regions(
    file: UploadFile = File(...),
    regions: str = Form(...),
):
    """
    按指定区域识别：用户框选/调整切分线后，只对这些区域做 OCR。

    regions: JSON 字符串，元素形如 {"x":0,"y":0,"w":100,"h":50}（像素，原图坐标）
    返回每个区域的行结构与拼接文本，供题目切分使用。
    区域面积远小于整页，因此这里做高质量识别（切条带 + 增强重试 + 行带排序）。
    """
    raw = await file.read()
    img = _decode_image(raw)
    try:
        rl = json.loads(regions)
    except Exception:
        raise HTTPException(status_code=400, detail="regions 必须是合法 JSON 数组")
    if not isinstance(rl, list):
        raise HTTPException(status_code=400, detail="regions 必须是数组")

    H, W = img.shape[:2]
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY) if img.ndim == 3 else img
    try:
        blanks = detect_blank_lines(gray)
    except Exception:  # noqa: BLE001
        blanks = []

    out = []
    for i, r in enumerate(rl):
        x = max(0, int(r.get("x", 0)))
        y = max(0, int(r.get("y", 0)))
        w = max(1, int(r.get("w", 1)))
        h = max(1, int(r.get("h", 1)))
        x2, y2 = min(W, x + w), min(H, y + h)
        w, h = max(1, x2 - x), max(1, y2 - y)

        lines = _recognize_area(img, x, y, w, h)
        if lines and blanks:
            local = [(bx - x, by - y, bw, bh) for (bx, by, bw, bh) in blanks
                     if x <= bx and bx + bw <= x2 and y <= by and by + bh <= y2]
            if local:
                insert_blanks(None, lines, local)
        try:
            conf = float(np.mean([l["score"] for l in lines])) if lines else 0.0
        except Exception:  # noqa: BLE001
            conf = 0.0
        out.append({
            "index": i,
            "x": x, "y": y, "w": w, "h": h,
            "content": "\n".join(l["text"] for l in lines),
            "confidence": round(conf, 4),
            "lineCount": len(lines),
            "lines": lines,
        })
    log.info("regions parsed: %dx%d, regions=%d", W, H, len(out))
    return {"width": W, "height": H, "regions": out}


@app.post("/ocr/recognize")
async def ocr_recognize(file: UploadFile = File(...)):
    """纯文字识别（不分区），用于答题卡考号/姓名区域等小图"""
    raw = await file.read()
    img = _decode_image(raw)
    lines = engine.recognize(img)
    return {
        "text": "".join(l["text"] for l in lines),
        "lines": lines,
    }


@app.post("/ocr/recognize_best")
async def ocr_recognize_best(file: UploadFile = File(...)):
    """高精度识别单题作答区（放大+增强重识别），用于人工打分时参考学生手写答案"""
    raw = await file.read()
    img = _decode_image(raw)
    lines = _recognize_best(img, force=True)
    return {
        "text": "\n".join(l["text"] for l in lines),
        "lines": lines,
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=int(os.environ.get("PORT", "8801")))
