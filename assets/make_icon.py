# -*- coding: utf-8 -*-
"""生成 ArchitectBlocks 项目图标：宝箱 + 放大镜 + 砖块纹理（扁平设计，圆角底板）"""
from PIL import Image, ImageDraw

S = 256
img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

def rr(box, radius, fill):
    d.rounded_rectangle(box, radius=radius, fill=fill)

# ---------- 背景：琥珀色圆角方 ----------
rr((0, 0, S - 1, S - 1), 52, "#F59E0B")

# ---------- 背景装饰：砖块纹理（半透明白） ----------
brick = (255, 255, 255, 42)
for y in range(24, 232, 44):
    d.line((24, y, 232, y), fill=brick, width=6)
    offset = 0 if (y // 44) % 2 == 0 else 38
    for x in range(40 + offset % 76, 240, 76):
        d.line((x, y - 38, x, y + 6 if y + 6 < y else y), fill=(255, 255, 255, 0), width=1)
# 竖缝
for row, x0 in [(0, 88), (0, 168), (1, 50), (1, 126), (1, 204)]:
    pass  # 简化处理，仅横线留白即可

# ---------- 宝箱主体 ----------
# 箱体
rr((48, 108, 208, 208), 16, "#8B5A2B")
# 箱盖
rr((40, 76, 216, 128), 18, "#A0522D")
# 盖沿高光
rr((40, 76, 216, 96), 14, "#B87333")
# 金属包边
d.rectangle((120, 108, 136, 208), fill="#FFD54F")
rr((110, 100, 146, 138), 10, "#FFD54F")
# 锁扣
d.ellipse((121, 114, 135, 130), fill="#795548")
d.rectangle((125, 124, 131, 140), fill="#FFD54F")
# 木纹线
d.line((60, 150, 112, 150), fill="#6D4C22", width=5)
d.line((144, 168, 200, 168), fill="#6D4C22", width=5)
d.line((60, 182, 104, 182), fill="#6D4C22", width=5)

# ---------- 放大镜（右上，象征搜索） ----------
lens_c = (192, 72)
lens_r = 34
d.ellipse((lens_c[0] - lens_r, lens_c[1] - lens_r,
           lens_c[0] + lens_r, lens_c[1] + lens_r),
          outline="#FFFFFF", width=12)
# 镜片高光弧
d.arc((lens_c[0] - lens_r + 10, lens_c[1] - lens_r + 10,
       lens_c[0] + lens_r - 10, lens_c[1] + lens_r - 10),
      start=200, end=300, fill="#E0F2FE", width=6)
# 镜柄
d.line((214, 98, 238, 122), fill="#FFFFFF", width=14)

img.save("assets/icon-256.png")
print("icon-256.png OK")

# 多分辨率输出
for size in (128, 64, 48, 32, 16):
    img.resize((size, size), Image.LANCZOS).save(f"assets/icon-{size}.png")
    print(f"icon-{size}.png OK")

ico_path = "assets/icon.ico"
imgs = [Image.open(f"assets/icon-{s}.png") for s in (256, 128, 64, 48, 32, 16)]
imgs[0].save(ico_path, format="ICO", append_images=imgs[1:], sizes=[(s, s) for s in (256, 128, 64, 48, 32, 16)])
print("icon.ico OK")
