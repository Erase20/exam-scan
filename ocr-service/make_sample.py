"""生成模拟试卷图片，用于端到端测试 OCR 与图文分区"""
import os
from PIL import Image, ImageDraw, ImageFont

W, H = 1654, 2339  # A4 @200dpi
img = Image.new("RGB", (W, H), "white")
d = ImageDraw.Draw(img)

def font(size, bold=False):
    for p in [
        r"C:\Windows\Fonts\msyhbd.ttc" if bold else r"C:\Windows\Fonts\msyh.ttc",
        r"C:\Windows\Fonts\simhei.ttf",
        r"C:\Windows\Fonts\simsun.ttc",
    ]:
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, size)
            except Exception:
                continue
    return ImageFont.load_default()

f_title = font(40, True)
f_body = font(26)
f_small = font(22)

y = 80
d.text((W // 2 - 200, y), "五年级数学期末检测卷", font=f_title, fill="black")
y += 70
d.text((W // 2 - 170, y), "（时间：90分钟  满分：100分）", font=f_small, fill="black")
y += 50
d.text((120, y), "班级：__________  姓名：__________  学号：__________", font=f_small, fill="black")
y += 60

def line(text, size=None, indent=120, gap=44, bold=False):
    global y
    d.text((indent, y), text, font=size or f_body, fill="black")
    y += gap

def draw_figure(x, y0, w, h):
    """画一个几何示意图（矩形+圆+标注），模拟试卷中的图片区域"""
    d.rectangle([x, y0, x + w, y0 + h], outline="black", width=3)
    d.ellipse([x + 30, y0 + 30, x + w - 30, y0 + h - 30], outline="black", width=3)
    d.line([x + w // 2, y0, x + w // 2, y0 + h], fill="black", width=2)
    d.line([x, y0 + h // 2, x + w, y0 + h // 2], fill="black", width=2)
    d.text((x - 10, y0 + h + 8), "图1", font=f_small, fill="black")

line("一、选择题（每题2分，共10分）", f_body, 100, 50, True)
line("1. 下面各数中，最接近1的是（  ）。", f_body, 120, 40)
line("A. 0.9    B. 1.05    C. 0.98    D. 1.2", f_small, 150, 44)
line("2. 一个长方形的长是8厘米，宽是5厘米，它的周长是（  ）厘米。", f_body, 120, 40)
line("A. 13    B. 26    C. 40    D. 80", f_small, 150, 44)
line("3. 把3米长的绳子平均分成5段，每段是全长的（  ）。", f_body, 120, 40)
line("A. 3/5    B. 1/5    C. 1/3    D. 5/3", f_small, 150, 56)

line("二、填空题（每题2分，共6分）", f_body, 100, 50, True)
line("4. 3.25读作__________，它是由3个一、2个0.1和5个__________组成的。", f_body, 120, 40)
line("5. 一个三角形的两个内角分别是45°和60°，第三个内角是__________度。", f_body, 120, 56)

# 图片区域
d.text((120, y), "6. 观察下面的图形，回答问题。", font=f_body, fill="black")
y += 46
draw_figure(200, y, 420, 300)
y += 340

line("三、判断题（每题2分，共4分）", f_body, 100, 50, True)
line("7. 两条直线不相交就一定平行。（  ）", f_body, 120, 44)
line("8. 所有的偶数都是合数。（  ）", f_body, 120, 56)

line("四、解答题（每题6分，共12分）", f_body, 100, 50, True)
line("9. 水果店运来一批苹果，第一天卖出总数的1/4，第二天卖出总数的3/8，", f_body, 120, 40)
line("还剩90千克。这批苹果一共有多少千克？", f_body, 140, 44)
line("10. 计算下面各题：2.5×3.2×1.25", f_body, 120, 44)

os.makedirs(os.path.join(os.path.dirname(__file__), "samples"), exist_ok=True)
out = os.path.join(os.path.dirname(__file__), "samples", "test_paper.png")
img.save(out, "PNG")
print("saved:", out, img.size)
