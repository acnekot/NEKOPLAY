# 用 peek_cat.py 生成各平台图标：python3 build_assets.py（在 design/icon 下运行）
import subprocess, os
from PIL import Image
R = "../.."; ARGS = ["2.38", "0", ".64", ".825"]
run = lambda *a: subprocess.run(["python3", "peek_cat.py", *ARGS, *a], check=True)
BG = (232, 229, 221, 255)

# 1) 完整图标（圆角）+ 无圆角方图
run(); full = Image.open("full.png").convert("RGBA"); fg = Image.open("fg.png").convert("RGBA")
square = Image.alpha_composite(Image.new("RGBA", (1024, 1024), BG), fg)
icon_nobg = fg.copy(); icon_nobg.save("icon_nobg.png")

# 桌面：四周留白（macOS 风格）
pad = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0)); pad.alpha_composite(full.resize((900, 900), Image.LANCZOS), (62, 62))
pad.save(f"{R}/desktop/icons/icon.png"); pad.resize((256, 256), Image.LANCZOS).save(f"{R}/desktop/src/main/resources/icon.png")
pad.save(f"{R}/desktop/icons/icon.ico", sizes=[(s, s) for s in (16, 24, 32, 48, 64, 128, 256)])
pad.save(f"{R}/desktop/icons/icon.icns")
# iOS：不带圆角、不透明的方图（系统自己切圆角）
square.convert("RGB").save(f"{R}/ios/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/icon-1024.png")

# 2) 安卓自适应图标：前景多留一点边
run(".05"); afg = Image.open("fg.png").convert("RGBA"); shape = Image.open("shape.png").convert("RGBA")
# 单色图标：只要黑色部分（眼睛留空）
a = shape.split()[3]; dark = shape.convert("L").point(lambda v: 255 if v < 128 else 0)
from PIL import ImageChops
mono = Image.new("RGBA", shape.size, (255, 255, 255, 0)); mono.putalpha(ImageChops.multiply(a, dark))
for d, k in {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}.items():
    o = f"{R}/app/src/main/res/mipmap-{d}"; n = int(108 * k); L = int(48 * k)
    afg.resize((n, n), Image.LANCZOS).save(f"{o}/ic_launcher_fg.png")
    mono.resize((n, n), Image.LANCZOS).save(f"{o}/ic_launcher_mono.png")
    Image.new("RGBA", (n, n), BG).save(f"{o}/ic_launcher_bg.png")
    full.resize((L, L), Image.LANCZOS).save(f"{o}/ic_launcher.png")

# 最后把 full.png / fg.png 恢复成正常版本（不带自适应留边）
run()
for f in ("peek.svg", "peek_raw.png", "shape.png"):
    if os.path.exists(f): os.remove(f)
print("ok")
