"""
OCR 引擎封装

默认使用 RapidOCR（PP-OCRv4 中英文模型 + ONNX Runtime），特点：
  - 无需安装 PaddlePaddle，pip 即装即用
  - CPU 推理，A4 单页约 1~3 秒
  - 中文印刷体识别率高，适合试卷场景

引擎可插拔：设置环境变量 OCR_ENGINE=rapidocr|paddle|custom
"""
from __future__ import annotations

import logging
import os
from typing import List

import numpy as np

log = logging.getLogger("ocr-engine")


class OcrEngine:
    def __init__(self):
        self.name = os.environ.get("OCR_ENGINE", "rapidocr")
        self.ready = False
        self._impl = None
        self._init_impl()

    def _init_impl(self):
        if self.name == "rapidocr":
            try:
                from rapidocr_onnxruntime import RapidOCR
                self._impl = RapidOCR()
                self.ready = True
                log.info("RapidOCR 初始化成功")
            except Exception as e:  # noqa: BLE001
                log.error("RapidOCR 初始化失败: %s", e)
                self._impl = None
                self.ready = False
        elif self.name == "paddle":
            try:
                from paddleocr import PaddleOCR
                self._impl = PaddleOCR(use_angle_cls=True, lang="ch", show_log=False)
                self.ready = True
                log.info("PaddleOCR 初始化成功")
            except Exception as e:  # noqa: BLE001
                log.error("PaddleOCR 初始化失败: %s", e)
                self.ready = False
        else:
            log.warning("未知 OCR 引擎: %s", self.name)

    def recognize(self, image: np.ndarray) -> List[dict]:
        """
        识别图像中的文字行。
        统一输出： [{"text","score","cx","cy","minX","maxX","minY","maxY"}, ...]
        """
        if self._impl is None or image is None or image.size == 0:
            return []
        if self.name == "rapidocr":
            return self._recognize_rapid(image)
        if self.name == "paddle":
            return self._recognize_paddle(image)
        return []

    # ---------------- RapidOCR ----------------
    def _recognize_rapid(self, image: np.ndarray) -> List[dict]:
        try:
            result, _ = self._impl(image)
        except Exception as e:  # noqa: BLE001
            log.warning("RapidOCR 识别异常: %s", e)
            return []
        if not result:
            return []
        lines = []
        for item in result:
            box, text, score = item[0], item[1], item[2]
            xs = [p[0] for p in box]
            ys = [p[1] for p in box]
            lines.append({
                "text": str(text),
                "score": float(score),
                "minX": int(min(xs)),
                "maxX": int(max(xs)),
                "minY": int(min(ys)),
                "maxY": int(max(ys)),
                "cy": int((min(ys) + max(ys)) / 2),
                "cx": int((min(xs) + max(xs)) / 2),
            })
        return lines

    # ---------------- PaddleOCR ----------------
    def _recognize_paddle(self, image: np.ndarray) -> List[dict]:
        try:
            result = self._impl.ocr(image, cls=True)
        except Exception as e:  # noqa: BLE001
            log.warning("PaddleOCR 识别异常: %s", e)
            return []
        if not result or not result[0]:
            return []
        lines = []
        for item in result[0]:
            box, (text, score) = item[0], item[1]
            xs = [p[0] for p in box]
            ys = [p[1] for p in box]
            lines.append({
                "text": str(text),
                "score": float(score),
                "minX": int(min(xs)),
                "maxX": int(max(xs)),
                "minY": int(min(ys)),
                "maxY": int(max(ys)),
                "cy": int((min(ys) + max(ys)) / 2),
                "cx": int((min(xs) + max(xs)) / 2),
            })
        return lines
