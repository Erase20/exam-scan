"""
版面分析核心算法：图文区域划分

思路（不依赖重型深度学习模型，纯 OpenCV + 几何规则，速度极快且可控）：
1. 灰度化 + 自适应二值化，得到"墨迹掩膜"
2. 形态学膨胀把相邻字符连成"文字行"，再纵向连成"文字块"
3. 连通域分析找出所有"墨迹团"
4. 依据几何特征判定区块类型：
   - 长宽比极大且窄 -> 文字行
   - 团块面积大、内部墨迹稀疏、形状接近方形 -> 图片
   - 规则的细长横/竖线密集 -> 表格
5. 聚类合并相邻文字行为段落区块
6. 输出按阅读顺序(先上后下、先左后右)排序的区块列表
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import List

import cv2
import numpy as np


@dataclass
class Block:
    """版面区块"""
    x: int
    y: int
    w: int
    h: int
    block_type: str  # TEXT / IMAGE / TABLE
    confidence: float = 0.0
    # 是否为"强证据"图片：灰度层次丰富的真照片/插图。
    # 只有强证据图片区才会排除其中的文字行；
    # 几何示意图、表格等弱证据区域里的文字必须保留，否则正文会被整块丢掉。
    strong: bool = False
    content: str = ""
    sort_no: int = 0
    # OCR 结果中属于该区块的行，供后续题目切分使用
    lines: List[dict] = field(default_factory=list)

    def to_dict(self):
        return {
            "x": self.x,
            "y": self.y,
            "w": self.w,
            "h": self.h,
            "blockType": self.block_type,
            "confidence": round(self.confidence, 4),
            "content": self.content,
            "sortNo": self.sort_no,
            "lines": self.lines,
        }


def deskew(gray: np.ndarray) -> tuple[np.ndarray, float]:
    """基于最小外接矩形估算倾斜角并校正（扫描件常见 0.5~3 度歪斜）"""
    # 反色后计算非零像素的拟合
    inv = cv2.bitwise_not(gray)
    thr = cv2.threshold(inv, 0, 255, cv2.THRESH_BINARY | cv2.THRESH_OTSU)[1]
    # np.where 返回 (行,列)，而 minAreaRect 需要 (x,y)；坐标顺序写反会让
    # 估出的角度符号相反，旋转后倾斜翻倍（识别率反而大幅下降）。
    coords = np.column_stack(np.where(thr > 0))[:, ::-1].astype(np.float32)
    if len(coords) < 500:
        return gray, 0.0
    angle = cv2.minAreaRect(coords)[-1]
    # OpenCV 返回角度约定不一，统一到 [-45, 45]
    if angle < -45:
        angle = 90 + angle
    elif angle > 45:
        angle = angle - 90
    # 只处理小角度倾斜，大角度视为误判
    if abs(angle) < 0.3 or abs(angle) > 10:
        return gray, 0.0
    h, w = gray.shape[:2]
    m = cv2.getRotationMatrix2D((w / 2, h / 2), angle, 1.0)
    rotated = cv2.warpAffine(
        gray, m, (w, h),
        flags=cv2.INTER_CUBIC,
        borderMode=cv2.BORDER_CONSTANT,
        borderValue=255,
    )
    return rotated, angle


def get_ink_mask(gray: np.ndarray) -> np.ndarray:
    """得到墨迹掩膜（白底黑字 -> 字为 255）"""
    blur = cv2.GaussianBlur(gray, (3, 3), 0)
    binary = cv2.adaptiveThreshold(
        blur, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
        cv2.THRESH_BINARY_INV, 31, 15,
    )
    # 去掉孤立噪点
    kernel = np.ones((2, 2), np.uint8)
    binary = cv2.morphologyEx(binary, cv2.MORPH_OPEN, kernel)
    return binary


def _ink_density(mask: np.ndarray, x: int, y: int, w: int, h: int) -> float:
    """区域内墨迹像素占比"""
    roi = mask[y:y + h, x:x + w]
    if roi.size == 0:
        return 0.0
    return float(np.count_nonzero(roi)) / float(roi.size)


def _line_density(mask: np.ndarray, x: int, y: int, w: int, h: int) -> float:
    """
    检测规则直线（表格特征）：
    用长横/竖核做开运算，看剩下的像素占比。
    表格边框会大量保留，文字几乎被滤掉。
    """
    roi = mask[y:y + h, x:x + w]
    if roi.size == 0:
        return 0.0
    horiz = cv2.morphologyEx(
        roi, cv2.MORPH_OPEN,
        cv2.getStructuringElement(cv2.MORPH_RECT, (max(15, w // 4), 1)),
    )
    vert = cv2.morphologyEx(
        roi, cv2.MORPH_OPEN,
        cv2.getStructuringElement(cv2.MORPH_RECT, (1, max(15, h // 4))),
    )
    lines = cv2.bitwise_or(horiz, vert)
    return float(np.count_nonzero(lines)) / float(roi.size)


def _midtone_ratio(gray: np.ndarray, x: int, y: int, w: int, h: int) -> float:
    """
    中间调像素占比（灰度 70~200）。
    照片灰度层次丰富，中间调占比高（>0.25）；
    白底黑字的文字页灰度双峰分布，中间调占比极低（<0.05）。
    """
    roi = gray[y:y + h, x:x + w]
    if roi.size == 0:
        return 0.0
    return float(np.count_nonzero((roi > 70) & (roi < 200))) / float(roi.size)


def _detect_photo_regions(gray: np.ndarray, ink: np.ndarray) -> List[Block]:
    """
    检测图片区域。

    两种典型情况：
    A. 照片/插图：灰度层次丰富（方差大 + 中间调多），边缘多
    B. 几何示意图/函数图像：白底黑线，但内部大面积留白且无成行文字
    这里用"多尺度滑窗 + 特征打分"的轻量方式，兼顾两类。
    注意：白底黑字文字页的灰度 std 也会很高（双峰分布），
    因此必须叠加"中间调占比"与"文字带数量"两个强区分特征，否则会整页误判。
    """
    h, w = gray.shape[:2]
    blocks: List[Block] = []
    min_side = max(60, int(min(w, h) * 0.10))

    # 用形态学闭运算把图片整体连成一团（文字行间距小，会被单独处理）
    closed = cv2.morphologyEx(
        ink, cv2.MORPH_CLOSE,
        cv2.getStructuringElement(cv2.MORPH_RECT, (25, 25)),
    )
    contours, _ = cv2.findContours(closed, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

    for c in contours:
        x, y, bw, bh = cv2.boundingRect(c)
        if bh < 40 or bw < 40:
            continue

        density = _ink_density(ink, x, y, bw, bh)
        ldens = _line_density(ink, x, y, bw, bh)
        aspect = bw / max(bh, 1)
        midtone = _midtone_ratio(gray, x, y, bw, bh)
        area_ratio = (bw * bh) / float(w * h)

        # 小型插图：尺寸不足 min_side，但近方形、有实际墨迹、非细长文字行
        # （试卷中的小插图/实物图常只有 100~250px，细长文字行用宽高比排除）
        if bw < min_side or bh < min_side:
            if (bw >= 100 and bh >= 100
                    and density > 0.05 and density < 0.35
                    and ldens > 0.002 and aspect < 2.5):
                blocks.append(Block(x, y, bw, bh, "IMAGE", confidence=0.65))
            continue

        # 区域灰度方差：照片远高于文字
        roi_gray = gray[y:y + bh, x:x + bw]
        std = float(np.std(roi_gray))

        # 判定规则：
        # 1) 灰度层次丰富（方差大）-> 照片
        #    例外：占页面过半的大区域必须叠加"中间调占比"检查——
        #    白底黑字整页文字的 std 也很高（双峰分布），但中间调极低；
        #    小面积插图（示意图等）面积占比低，保持原判定即可
        if std > 42 and density > 0.08 and aspect < 4.0:
            if area_ratio <= 0.5 or midtone > 0.22:
                # 有中间调层次或面积较小的，才是真照片/插图 -> 强证据
                blocks.append(Block(x, y, bw, bh, "IMAGE",
                                    confidence=min(0.95, std / 80),
                                    strong=midtone > 0.20 or area_ratio <= 0.25))
                continue
        # 2) 墨迹稀疏 + 有明显直线 + 不太像整段文字 -> 示意图/表格
        #    这类区域常夹杂说明文字，不能排除文字行，故 strong=False
        if density < 0.16 and ldens > 0.002 and aspect < 5.0 and bh > min_side:
            kind = "TABLE" if ldens > 0.012 else "IMAGE"
            blocks.append(Block(x, y, bw, bh, kind, confidence=0.72, strong=False))
            continue
        # 3) 大面积白区但有少量墨迹且高宽比接近方形 -> 图片（同样是弱证据）
        if density < 0.12 and 0.4 < aspect < 3.0 and bh > min_side * 1.2:
            blocks.append(Block(x, y, bw, bh, "IMAGE", confidence=0.6, strong=False))

    return _dedup_nested(blocks)


def _dedup_nested(blocks: List[Block]) -> List[Block]:
    """去掉被更大同类型区块完全包含的小区块"""
    if not blocks:
        return []
    blocks = sorted(blocks, key=lambda b: b.w * b.h, reverse=True)
    kept: List[Block] = []
    for b in blocks:
        nested = False
        for k in kept:
            if (b.x >= k.x - 5 and b.y >= k.y - 5
                    and b.x + b.w <= k.x + k.w + 5
                    and b.y + b.h <= k.y + k.h + 5):
                nested = True
                break
        if not nested:
            kept.append(b)
    return kept


def _overlaps(a: Block, b: Block, ratio: float = 0.35) -> bool:
    """a 与 b 的重叠面积占 a 的比例是否超过阈值"""
    ix = max(0, min(a.x + a.w, b.x + b.w) - max(a.x, b.x))
    iy = max(0, min(a.y + a.h, b.y + b.h) - max(a.y, b.y))
    inter = ix * iy
    area = max(a.w * a.h, 1)
    return inter / area > ratio


def detect_text_rows(ink: np.ndarray, excluded: List[Block]) -> List[tuple]:
    """
    检测文字行候选区域。用横向膨胀把单字连成行，再取连通域。
    返回 (x, y, w, h) 列表。

    排除策略：只排除落在"强证据图片区"（真照片/插图）里的行。
    几何示意图、表格等区域常同时含有说明文字，若一并排除会整块丢失正文。
    """
    excluded = [e for e in (excluded or []) if getattr(e, "strong", False)]
    h, w = ink.shape[:2]
    # 估算字符高度：试卷正文大约 28~42px（300dpi A4）
    kw = max(12, int(w * 0.012))
    kh = max(3, int(h * 0.004))
    # 先横向膨胀连成行（连接字间距），纵向轻微膨胀连接笔画
    dil = cv2.dilate(
        ink,
        cv2.getStructuringElement(cv2.MORPH_RECT, (kw, kh)),
        iterations=2,
    )
    contours, _ = cv2.findContours(dil, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

    rows = []
    for c in contours:
        x, y, bw, bh = cv2.boundingRect(c)
        if bh < 8 or bw < 12:
            continue
        # 过高的区域（跨越多行）先接受，后续 OCR 时按墨迹行间隙切成条带
        # 阈值放宽到 0.9，避免密集正文被膨胀连成一整团后判定为无效而整页丢失
        if bh > h * 0.9:
            continue
        cand = Block(x, y, bw, bh, "TEXT")
        if any(_overlaps(cand, ex, 0.5) for ex in excluded):
            continue
        rows.append((x, y, bw, bh))
    return rows


def merge_rows_to_blocks(rows: List[tuple], page_w: int, page_h: int) -> List[Block]:
    """
    把文字行合并成段落区块：
    - 水平投影重叠度 > 60% 视为同一栏
    - 行间距 < 1.5 倍行高视为同一段
    """
    if not rows:
        return []
    rows = sorted(rows, key=lambda r: (r[1], r[0]))
    used = [False] * len(rows)
    blocks: List[Block] = []

    for i, r in enumerate(rows):
        if used[i]:
            continue
        cur = [r[0], r[1], r[0] + r[2], r[1] + r[3]]
        used[i] = True
        changed = True
        while changed:
            changed = False
            for j, o in enumerate(rows):
                if used[j]:
                    continue
                # 水平重叠
                ox1, oy1, ox2, oy2 = o[0], o[1], o[0] + o[2], o[1] + o[3]
                overlap = max(0, min(cur[2], ox2) - max(cur[0], ox1))
                min_w = max(1, min(cur[2] - cur[0], o[2]))
                if overlap / min_w < 0.5:
                    continue
                # 垂直距离
                line_h = min(cur[3] - cur[1], o[3])
                idx_gap = o[1] - cur[3]
                below = -line_h * 2 < idx_gap < line_h * 1.1
                above = 0 < cur[1] - oy2 < line_h * 1.1
                if below or above:
                    cur = [min(cur[0], ox1), min(cur[1], oy1),
                           max(cur[2], ox2), max(cur[3], oy2)]
                    used[j] = True
                    changed = True

        blocks.append(Block(cur[0], cur[1], cur[2] - cur[0], cur[3] - cur[1], "TEXT"))
    return blocks


def _band_order(blocks: List[Block]) -> List[Block]:
    """按 y 聚类成"带"、带内按 x 排序；容忍同一行区块的轻微上下错位"""
    if not blocks:
        return []
    bands: List[List[Block]] = []
    for b in sorted(blocks, key=lambda b: b.y):
        placed = False
        for band in bands:
            for o in band:
                ov = min(b.y + b.h, o.y + o.h) - max(b.y, o.y)
                if ov > 0.5 * min(b.h, o.h):
                    band.append(b)
                    placed = True
                    break
            if placed:
                break
        if not placed:
            bands.append([b])
    result: List[Block] = []
    for band in bands:
        band.sort(key=lambda b: b.x)
        result.extend(band)
    return result


def find_column_gutter(ink: np.ndarray):
    """
    检测双栏/跨页试卷的中缝 x 坐标（找不到时返回 None）。

    做法：按列统计墨迹密度，在页面中部 32%~68% 区间找一段"连续且几乎空白"的竖带。
    单栏页面中部通常处处有字，不会命中；两栏或跨页扫描件则会留出明显中缝。
    """
    h, w = ink.shape[:2]
    proj = ink.sum(axis=0).astype(np.float32) / max(1, h)
    k = max(3, int(w * 0.006)) | 1
    smooth = np.convolve(proj, np.ones(k, dtype=np.float32) / k, mode="same")
    lo, hi = int(w * 0.32), int(w * 0.68)
    if hi - lo < 20:
        return None
    base = float(np.percentile(smooth[lo:hi], 75))
    if base <= 0.002:  # 中部本来就没字，不构成"双栏"
        return None
    thr = max(0.001, base * 0.12)
    best = (0, 0, 0)
    i = lo
    while i < hi:
        if smooth[i] < thr:
            j = i
            while j < hi and smooth[j] < thr:
                j += 1
            if j - i > best[0]:
                best = (j - i, i, j)
            i = j
        else:
            i += 1
    width, s, e = best
    if width < max(12, w * 0.015):
        return None
    return (s + e) // 2


def reading_order(blocks: List[Block], gutter: int | None = None) -> List[Block]:
    """
    阅读顺序：按 y 聚类成"带"，带内按 x 排序。

    双栏/跨页试卷必须"先左栏从上到下，再右栏从上到下"。若按整页 y 排序，
    左右两栏同一高度的文字会交错串在一起，题目切分就会把两道题拼成一题。
    """
    if not blocks:
        return []
    if gutter is not None:
        left = [b for b in blocks if b.x + b.w / 2 < gutter]
        right = [b for b in blocks if b.x + b.w / 2 >= gutter]
        if left and right:
            result = _band_order(left) + _band_order(right)
            for i, b in enumerate(result):
                b.sort_no = i
            return result
    result = _band_order(blocks)
    for i, b in enumerate(result):
        b.sort_no = i
    return result


def _deskew_color(image: np.ndarray, angle: float) -> np.ndarray:
    """彩色图用与灰度图相同的旋转矩阵校正，保证版面框与 OCR 裁剪坐标一致"""
    if abs(angle) < 0.3:
        return image
    h, w = image.shape[:2]
    m = cv2.getRotationMatrix2D((w / 2, h / 2), angle, 1.0)
    return cv2.warpAffine(
        image, m, (w, h), flags=cv2.INTER_CUBIC,
        borderMode=cv2.BORDER_CONSTANT,
        borderValue=(255, 255, 255) if image.ndim == 3 else 255,
    )


def analyze_layout(image: np.ndarray):
    """
    主入口：输入 BGR 图像，返回 (区块列表, 校正后的灰度图, 倾斜角, 校正后的彩色图, 中缝x)

    灰度图与彩色图必须同步去倾斜：过去只校正灰度图（用于版面分析），
    OCR 却仍在歪斜的彩色图上裁剪，框与字错位，倾斜扫描件会明显漏字。
    """
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY) if image.ndim == 3 else image
    gray, angle = deskew(gray)
    image = _deskew_color(image, angle)
    ink = get_ink_mask(gray)

    graphics = _detect_photo_regions(gray, ink)
    rows = detect_text_rows(ink, graphics)
    h, w = gray.shape[:2]
    texts = merge_rows_to_blocks(rows, w, h)

    gutter = find_column_gutter(ink)
    all_blocks = graphics + texts
    return reading_order(all_blocks, gutter), gray, angle, image, gutter
