"""
填空题横线还原

试卷里的填空位是"印刷直线"，OCR 引擎检测不到对应的字形，通常会整条丢弃，
导致题干里的空彻底丢失（或只识别出零星符号）。

本模块用形态学把这些横线找出来，再按它在行内的**横坐标比例**
把「（　　）」插回对应文字行，从而还原成可读的填空形式。
"""
from __future__ import annotations

import logging

import cv2
import numpy as np

log = logging.getLogger("blank")

# 插入文本：左右括号（中间两个全角空格，便于人工填写）
BLANK_TEXT = "（　　）"


def get_ink(gray: np.ndarray) -> np.ndarray:
    """白底黑字 -> 墨迹掩膜（字为 255）"""
    blur = cv2.GaussianBlur(gray, (3, 3), 0)
    binary = cv2.adaptiveThreshold(
        blur, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY_INV, 31, 15,
    )
    kernel = np.ones((2, 2), np.uint8)
    return cv2.morphologyEx(binary, cv2.MORPH_OPEN, kernel)


def detect_blank_lines(gray: np.ndarray, ink: np.ndarray | None = None) -> list[tuple[int, int, int, int]]:
    """
    检测填空横线，返回 [(x, y, w, h)]（图像原坐标）。

    判定依据：水平细长、内部墨迹密实、上下有留白（区别于表格线/下划线）。
    """
    h, w = gray.shape[:2]
    if ink is None:
        ink = get_ink(gray)

    min_len = max(40, int(w * 0.035))      # 太短的短线多半是笔画
    max_len = int(w * 0.55)                # 过长的多半是表格边框/分隔线
    max_thick = max(6, int(h * 0.004))     # 填空横线很薄

    # 横向开运算：只保留足够长的水平线段
    horiz = cv2.morphologyEx(
        ink, cv2.MORPH_OPEN,
        cv2.getStructuringElement(cv2.MORPH_RECT, (min_len, 1)),
    )
    contours, _ = cv2.findContours(horiz, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

    out: list[tuple[int, int, int, int]] = []
    for c in contours:
        x, y, bw, bh = cv2.boundingRect(c)
        if bw < min_len or bw > max_len or bh > max_thick:
            continue
        roi = ink[y:y + bh, x:x + bw]
        if roi.size == 0:
            continue
        # 线本身应当是实心的
        if float(np.count_nonzero(roi)) / float(roi.size) < 0.6:
            continue
        # 上下要有留白：紧贴文字的下划线/表格线不算填空位
        pad = max(3, bh * 2)
        above = ink[max(0, y - pad):y, x:x + bw]
        below = ink[y + bh:y + bh + pad, x:x + bw]
        for nb in (above, below):
            if nb.size and float(np.count_nonzero(nb)) / float(nb.size) > 0.2:
                break
        else:
            out.append((x, y, bw, bh))

    out = _filter_grid_lines(out)
    if out:
        log.info("检测填空横线 %d 处", len(out))
    return out


def _filter_grid_lines(items: list[tuple[int, int, int, int]]) -> list[tuple[int, int, int, int]]:
    """
    剔除表格/装饰性的重复横线：同一列（x、宽度接近）出现 3 条以上
    且纵向间距均匀时，认定为表格线而非填空位。
    """
    groups: list[list[tuple[int, int, int, int]]] = []
    for it in items:
        x, y, w, h = it
        placed = False
        for g in groups:
            if abs(g[0][0] - x) <= 20 and abs(g[0][2] - w) <= max(20, 0.15 * w):
                g.append(it)
                placed = True
                break
        if not placed:
            groups.append([it])

    kept: list[tuple[int, int, int, int]] = []
    dropped = 0
    for raw in groups:
        g = sorted(raw, key=lambda t: t[1])
        if len(g) >= 3:
            # 用相邻间距的"中位数"找规则重复线，避免少量噪声线影响整体判定
            gaps = [g[i + 1][1] - g[i][1] for i in range(len(g) - 1)]
            med = sorted(gaps)[len(gaps) // 2]
            regular = set()
            for i, gp in enumerate(gaps):
                if abs(gp - med) <= max(20, 0.3 * med):
                    regular.add(i)
                    regular.add(i + 1)
            if len(regular) >= 3:
                for i, t in enumerate(g):
                    if i not in regular:
                        kept.append(t)
                    else:
                        dropped += 1
                continue
        kept.extend(g)
    if dropped:
        log.info("过滤表格/重复横线 %d 条", dropped)
    return kept


def _insert_one(text: str, pos: int) -> str:
    pos = max(0, min(len(text), pos))
    return text[:pos] + BLANK_TEXT + text[pos:]


def insert_blanks(image: np.ndarray, lines: list[dict], blanks: list[tuple[int, int, int, int]] | None = None) -> list[dict]:
    """
    把检测到的横线以「（　　）」插回所属文字行的对应位置。
    位置按"横线中心 x 在该行文字跨度中的比例 × 字符数"估算（印刷体近似等宽）。
    """
    if not lines or not blanks:
        return lines

    # 每条横线只归入纵向最接近的一行，避免相邻行互相抢同一条横线
    assign: dict[int, list[tuple[int, int]]] = {}
    for (bx, by, bw, bh) in blanks:
        bc = by + bh / 2
        best, best_d = None, None
        for i, ln in enumerate(lines):
            if not ln.get("text"):
                continue
            x1, x2 = int(ln["minX"]), int(ln["maxX"])
            y1, y2 = int(ln["minY"]), int(ln["maxY"])
            tol = max(8, (y2 - y1) * 0.6)
            if not (y1 - tol <= bc <= y2 + tol):
                continue
            margin = max(80, (x2 - x1) * 0.5)
            if bx + bw < x1 - margin or bx > x2 + margin:
                continue
            d = 0 if y1 <= bc <= y2 else min(abs(bc - y1), abs(bc - y2))
            if best_d is None or d < best_d:
                best, best_d = i, d
        if best is not None:
            assign.setdefault(best, []).append((bx, bw))

    for idx, hits in assign.items():
        ln = lines[idx]
        x1, x2 = int(ln["minX"]), int(ln["maxX"])
        span = max(1, x2 - x1)
        for n, (bx, bw) in enumerate(sorted(hits, key=lambda t: t[0])):
            center = bx + bw / 2
            if center >= x2:
                pos = len(ln["text"])
            elif center <= x1:
                pos = 0
            else:
                pos = int(round((center - x1) / span * len(ln["text"])))
            ln["text"] = _insert_one(ln["text"], pos + n * len(BLANK_TEXT))
    return lines
