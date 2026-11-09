"""
模拟学生填涂答题卡并保存为图片，用于验证阅卷流程
"""
import io
import json
import os
import sys
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BASE = os.path.dirname(os.path.abspath(__file__))


def load_layout_via_api(sheet_id):
    import urllib.request
    url = f"http://127.0.0.1:8090/api/answer-sheet/{sheet_id}"
    with urllib.request.urlopen(url) as r:
        d = json.loads(r.read().decode("utf-8"))
    return json.loads(d["data"]["layoutJson"])


def render(layout, answers, student_no, out_path):
    W = layout["pageWidth"]
    H = layout["pageHeight"]
    img = Image.new("RGB", (W, H), "white")
    d = ImageDraw.Draw(img)

    def font(sz, bold=False):
        for p in ([r"C:\Windows\Fonts\msyhbd.ttc"] if bold else []) + [
            r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\simsun.ttc"]:
            if os.path.exists(p):
                try:
                    return ImageFont.truetype(p, sz)
                except Exception:
                    pass
        return ImageFont.load_default()

    # 四角定位标记
    for m in layout["markers"]:
        x, y, s = m["x"], m["y"], m["size"]
        d.rectangle([x, y, x + s, y + s], fill="black")

    # 标题
    d.text((W // 2 - 380, 200), "答题卡（样本）", font=font(64, True), fill="black")

    info = layout["info"]
    nb = info["nameBox"]
    d.text((nb["x"], nb["y"] + 10), "姓名：张三", font=font(34), fill="black")
    cb = info["classBox"]
    d.text((cb["x"], cb["y"] + 10), "班级：五(1)班", font=font(34), fill="black")

    # 考号：旧版布局画涂卡区；新版布局没有涂卡区，把考号手写进手写格
    digits = info["idDigits"]
    if "idBubbleStartX" in info:
        db = info["idDigitBox"]
        d.text((db["x"], db["y"] - 20), "考号", font=font(30), fill="black")
        bx0 = info["idBubbleStartX"]
        gx = info["idBubbleGapX"]
        gy = info["idBubbleGapY"]
        r = info["idBubbleR"]
        startY = info["idStartY"]
        for i in range(digits):
            x = bx0 + i * gx
            for n in range(10):
                cy = startY + n * gy
                d.ellipse([x - r, cy - r, x + r, cy + r], outline="black", width=3)
                d.text((x - 7, cy - 12), str(n), font=font(22), fill="black")
        # 填涂考号
        for i, ch in enumerate(student_no):
            if ch.isdigit():
                x = bx0 + i * gx
                cy = startY + int(ch) * gy
                d.ellipse([x - r + 2, cy - r + 2, x + r - 2, cy + r - 2], fill="black")
    else:
        hb = info.get("idHandBox")
        if hb:
            hx, hy = hb["x"], hb["y"]
            d.text((hx, hy + 10), "考号", font=font(30), fill="black")
            for i, ch in enumerate(student_no[:digits]):
                if ch.isdigit():
                    sqx = hx + 90 + i * 80
                    d.text((sqx + 12, hy + 2), ch, font=font(44), fill="black")

    # 客观题填涂
    for cell in layout["objective"]["cells"]:
        box = cell["box"]
        d.text((box["x"] + 10, box["y"] + 30), str(cell["qNo"]) + ".", font=font(34, True), fill="black")
        chosen = answers.get(cell["paperItemId"])
        for b in cell["bubbles"]:
            cx, cy, rr = b["cx"], b["cy"], b["r"]
            filled = b["key"] == chosen
            if filled:
                d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], fill="black")
            else:
                d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], outline="black", width=3)
            if not filled:
                d.text((cx - 7, cy - 11), b["key"], font=font(22), fill="black")

    # 主观题框
    for cell in layout["subjective"]["cells"]:
        box = cell["box"]
        d.rectangle([box["x"], box["y"], box["x"] + box["w"], box["y"] + box["h"]],
                    outline=(0xCC, 0xCC, 0xCC), width=3)
        d.text((box["x"] + 20, box["y"] + 20), f'{cell["qNo"]}.（{cell["score"]}分）',
               font=font(34, True), fill="black")
        d.text((box["x"] + 40, box["y"] + 90), "解：由题意可得……（学生手写解答区域）",
               font=font(30), fill=(60, 60, 60))
        sb = cell["scoreBox"]
        d.rectangle([sb["x"], sb["y"], sb["x"] + sb["w"], sb["y"] + sb["h"]], outline="black", width=3)
        d.text((sb["x"] + 8, sb["y"] + 12), "得分", font=font(26), fill="black")

    img.save(out_path, "PNG")
    return out_path


if __name__ == "__main__":
    sheet_id = int(sys.argv[1]) if len(sys.argv) > 1 else 3
    layout = load_layout_via_api(sheet_id)
    cells = layout["objective"]["cells"]
    # 模拟作答：奇数题选A，偶数题选B
    answers = {}
    for i, c in enumerate(cells):
        keys = [b["key"] for b in c["bubbles"]]
        answers[c["paperItemId"]] = keys[i % len(keys)]
    out = os.path.join(BASE, "samples", "filled_sheet.png")
    render(layout, answers, "20240108", out)
    print("saved:", out, "cells:", len(cells))
