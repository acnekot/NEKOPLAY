# NEKOPLAY 宣传片：瑞士国际主义 + 动态排版，4K 120fps，150 BPM（每拍 0.4s = 48 帧）
import sys, os, math, subprocess
from functools import lru_cache
from PIL import Image, ImageDraw, ImageFont

W, H, FPS, BEAT = 3840, 2160, 120, 48
UP = '/mnt/user-data/uploads/'
PAPER = (236, 233, 225); INK = (14, 14, 14); BLUE = (79, 125, 247); DARK = (42, 47, 56); WHITE = (250, 250, 248)

F_LAT = '/usr/share/fonts/opentype/inter/InterDisplay-Black.otf'
F_LATB = '/usr/share/fonts/opentype/inter/InterDisplay-Bold.otf'
F_CJK = '/usr/share/fonts/opentype/noto/NotoSansCJK-Black.ttc'
F_MONO = '/root/.fonts/mono_700.ttf'

@lru_cache(None)
def font(kind, size):
    if kind == 'cjk': return ImageFont.truetype(F_CJK, size, index=2)
    return ImageFont.truetype({'lat': F_LAT, 'latb': F_LATB, 'mono': F_MONO}[kind], size)

@lru_cache(None)
def txt(s, size, color, kind='lat', outline=0, track=0):
    """文字精灵（带透明底），按需描边；track 为字距（px）"""
    f = font(kind, size)
    if track:
        parts = [txt(ch, size, color, kind, outline) for ch in s]
        w = sum(p.width for p in parts) + track * (len(parts) - 1)
        h = max(p.height for p in parts)
        im = Image.new('RGBA', (max(w, 1), h)); x = 0
        for p in parts: im.alpha_composite(p, (x, 0)); x += p.width + track
        return im
    l, t, r, b = f.getbbox(s, stroke_width=outline)
    asc, desc = f.getmetrics()
    im = Image.new('RGBA', (r - l + 2, asc + desc + outline * 2))
    d = ImageDraw.Draw(im)
    if outline: d.text((-l + 1, outline), s, font=f, fill=(0, 0, 0, 0), stroke_width=outline, stroke_fill=color + (255,))
    else: d.text((-l + 1, 0), s, font=f, fill=color + (255,))
    return im

@lru_cache(None)
def shot(name, w, crop=None, border=8):
    im = Image.open(UP + name + '-image.png').convert('RGB')
    if crop: im = im.crop(crop)
    h = round(im.height * w / im.width)
    im = im.resize((w, h), Image.LANCZOS)
    if border:
        out = Image.new('RGB', (w + border * 2, h + border * 2), INK); out.paste(im, (border, border)); im = out
    return im.convert('RGBA')

def ease(x):  # 快进慢停：适合「砸进来」
    x = min(max(x, 0.0), 1.0); return 1 - (1 - x) ** 5
def ein(x):
    x = min(max(x, 0.0), 1.0); return x ** 4
def seg(lb, a, d):  # 第 a 拍开始、持续 d 拍的进度
    return (lb - a) / d

def paste(bg, spr, x, y): bg.alpha_composite(spr, (int(x), int(y)))

def grid(im, color, alpha=40):
    d = ImageDraw.Draw(im); c = color + (alpha,)
    for i in range(1, 12): d.line([(i * W / 12, 0), (i * W / 12, H)], fill=c, width=2)
    for j in range(1, 6): d.line([(0, j * H / 6), (W, j * H / 6)], fill=c, width=2)

def label(im, s, x, y, color=INK, size=46):
    paste(im, txt(s, size, color, 'mono' if s.isascii() else 'cjk'), x, y)

def rule(im, x, y, w, color=INK, h=14):
    ImageDraw.Draw(im).rectangle([x, y, x + w, y + h], fill=color + (255,))

def base(color):
    return Image.new('RGBA', (W, H), color + (255,))

def hud(im, idx, title, color=INK):
    """瑞士风格角标：页码 / 标题 / BPM，所有场景统一位置"""
    label(im, f'{idx:02d}', 120, 96, color)
    label(im, title, 300, 96, color)
    label(im, 'NEKOPLAY / 2026', W - 720, 96, color)
    ImageDraw.Draw(im).line([(120, 180), (W - 120, 180)], fill=color + (255,), width=4)

# ======================= 场景 =======================
def s1(lb):  # 0-8 拍：黑底，NEKOPLAY 一行行往上堆叠，实心 / 描边交替，越来越快
    im = base(INK)
    lh = 640
    for k in range(int(lb * 2) + 1):  # 每半拍新加一行，从底部砸入，旧行被推上去
        age = lb * 2 - k
        slot = (math.floor(age) - 1 + ease((age % 1) / 0.5)) if age >= 1 else 0
        y = H - 820 - slot * lh
        if y < -800: continue
        x = 120 - (1 - ease(age / 0.5)) * 2600
        spr = txt('NEKOPLAY', 820, BLUE if k % 4 == 3 else WHITE, 'lat', 0 if k % 2 == 0 else 10)
        paste(im, spr, x, y)
    # 最后一拍：整屏蓝色闪 + 大字
    if lb >= 7.5:
        q = ease(seg(lb, 7.5, 0.5))
        ImageDraw.Draw(im).rectangle([0, 0, W, int(H * q)], fill=BLUE + (255,))
    label(im, '150 BPM', W - 520, H - 140, WHITE) if lb < 7.5 else None
    return im

def s2(lb):  # 8-16 拍：纸色网格，「听 / 歌 / 这件事 / 重新做一遍」逐拍砸入并堆叠
    im = base(PAPER); grid(im, INK, 26); hud(im, 1, 'A NATIVE MUSIC PLAYER')
    words = [('听', 900), ('歌', 900), ('这件事，', 420), ('重新做', 420), ('一遍。', 420)]
    xs = [120, 1020, 1920, 1920, 1920]; ys = [420, 420, 330, 830, 1330]
    for i, (w, sz) in enumerate(words):
        a = [0, 1, 2.5, 3.5, 4.5][i]
        p = ease(seg(lb, a, 0.55))
        if lb < a: continue
        spr = txt(w, sz, BLUE if i == 1 else INK, 'cjk')
        paste(im, spr, xs[i] + (1 - p) * 1400, ys[i])
    # 底部英文副标逐字快速出现
    sub = 'NETEASE CLOUD MUSIC · KOTLIN · COMPOSE · ANDROID + WINDOWS'
    k = int(max(0, lb - 5) * 18)
    if k: label(im, sub[:k], 120, H - 220, INK, 60)
    if lb > 6: rule(im, 120, H - 300, (W - 240) * ease(seg(lb, 6, 1.2)), BLUE, 22)
    return im

def s3(lb):  # 16-24 拍：深色界面截图砸入，局部放大逐拍切换，叠超大「LYRICS」
    im = base(DARK)
    big = txt('LYRICS', 900, (60, 66, 78), 'lat')
    paste(im, big, -200 - lb * 60, H - 860)
    cards = [('cc217e49', 2600, None), ('cc217e49', 2400, (1100, 230, 2240, 1400)), ('73060ab8', 2600, None), ('05bbe968', 2600, None)]
    i = min(int(lb / 2), 3); a = i * 2
    name, w, crop = cards[i]
    p = ease(seg(lb, a, 0.6))
    c = shot(name, w, crop)
    x = (W - c.width) / 2 + (1 - p) * (W if i % 2 == 0 else -W)
    y = (H - c.height) / 2 + 60
    paste(im, c, x, y)
    hud(im, 2, ['逐字歌词 / KARAOKE', '翻译 · 当前句放大', '播放队列 / QUEUE', '楼层评论 / COMMENTS'][i], WHITE)
    # 左下角大序号
    paste(im, txt(f'0{i + 1}', 300, BLUE, 'lat'), 120, H - 420)
    return im

def s4(lb):  # 24-32 拍：截图逐拍叠成一摞（堆叠感），斜向错位
    im = base(PAPER); grid(im, INK, 26); hud(im, 3, 'ONE CODEBASE / 两个平台')
    names = ['8786c037', 'be2d8b0b', '73060ab8', '05bbe968', 'cc217e49', 'c0b6a9b5', 'f692ce55', '71f70856']
    for i, n in enumerate(names):
        a = i * 0.75
        if lb < a: break
        p = ease(seg(lb, a, 0.5))
        c = shot(n, 1600)
        x = 2050 + i * 170
        y = 300 + i * 120 - (1 - p) * 1600
        paste(im, c, x, y)
    # 左侧大字堆叠
    for i, w in enumerate(['PC.', 'ANDROID.', 'SAME.']):
        a = [0.5, 2.5, 5][i]
        if lb < a: continue
        p = ease(seg(lb, a, 0.5))
        paste(im, txt(w, 300, BLUE if i == 2 else INK, 'lat'), 120 - (1 - p) * 1500, 330 + i * 340)
    if lb > 5.5: paste(im, txt('一套代码，手机和电脑一起跑', 120, INK, 'cjk'), 130, 1480)
    return im

FEATS = [('无缝播放', 'GAPLESS'), ('淡入淡出', 'CROSSFADE'), ('桌面歌词', 'DESKTOP LYRICS'), ('侧拉模式', 'SIDE DOCK'),
         ('评论楼层', 'THREADS'), ('本地下载', 'DOWNLOADS'), ('莫奈取色', 'MONET COLOR'), ('WebDAV 备份', 'BACKUP'),
         ('真实音质', 'FLAC 48K/24BIT'), ('内置插件', 'FEATURE TOGGLES'), ('听歌统计', 'STATS'), ('按钮布局', 'CUSTOM LAYOUT')]

def s5(lb):  # 32-40 拍：功能名每半拍换一个，前一个缩小堆到上面
    im = base(INK); hud(im, 4, 'FEATURES', WHITE)
    k = min(int(lb * 1.5), len(FEATS) - 1)
    # 已经出现过的往上叠成小字列表
    for j in range(k):
        cn, en = FEATS[j]
        y = 1650 - (k - j) * 120
        if y < 230: continue
        paste(im, txt(cn, 84, (120, 120, 120), 'cjk'), 2850, y)
        label(im, en, 3450, y + 22, (90, 90, 90), 40)
    cn, en = FEATS[k]
    p = ease((lb * 1.5 - k) / 0.6)
    paste(im, txt(f'{k + 1:02d}', 260, BLUE, 'lat'), 120, 300)
    paste(im, txt(cn, 400, WHITE, 'cjk'), 120 - (1 - p) * 700, 820)
    paste(im, txt(en, 150, BLUE, 'lat'), 130, 1420 + (1 - p) * 200)
    rule(im, 120, H - 260, (W - 240) * (k + p) / len(FEATS), BLUE, 22)
    return im

def s6(lb):  # 40-48 拍：数字砸屏（实测数据）
    im = base(BLUE); hud(im, 5, 'MEASURED ON WINDOWS', WHITE)
    stats = [('174', 'FPS', '显卡渲染跑满刷新率'), ('−170', 'MB', '软件渲染可省内存'), ('9', 'PLUGINS', '内置插件随开随关'), ('2', 'PLATFORMS', 'Android + Windows')]
    i = min(int(lb / 2), 3)
    p = ease(seg(lb, i * 2, 0.5))
    n, unit, cap = stats[i]
    num = txt(n, 1100, WHITE, 'lat')
    paste(im, num, 100, 260 + (1 - p) * 900)
    paste(im, txt(unit, 220, INK, 'lat'), 120 + num.width + 40, 1040 - (1 - p) * 300)
    paste(im, txt(cap, 120, WHITE, 'cjk'), 140, 1560)
    # 前一个数字残影（堆叠）
    if i > 0:
        paste(im, txt(stats[i - 1][0], 300, (60, 100, 220), 'lat'), W - 1300, 260)
    return im

def s7(lb):  # 48-56 拍：NEKOPLAY 竖向堆叠 + 截图条，越来越快
    im = base(PAPER)
    speed = 1 + lb * 0.5
    off = (lb * 220 * speed) % 300
    for r in range(-1, 9):
        y = r * 300 - off + 120
        spr = txt('NEKOPLAY', 300, BLUE if r % 2 else INK, 'lat', 0 if r % 2 else 5)
        dx = (lb * 400 * (1 if r % 2 else -1)) % spr.width
        paste(im, spr, -dx, y); paste(im, spr, -dx + spr.width + 80, y)
    # 中间一张截图逐拍切换
    names = ['cc217e49', '8786c037', '05bbe968', '73060ab8', 'be2d8b0b', 'c0b6a9b5', 'f692ce55', 'cc217e49']
    c = shot(names[min(int(lb), 7)], 2200)
    paste(im, c, (W - c.width) / 2, (H - c.height) / 2)
    if lb > 7.5:
        q = ease(seg(lb, 7.5, 0.5)); ImageDraw.Draw(im).rectangle([0, 0, W, int(H * q)], fill=INK + (255,))
    return im

ICON = Image.open('/home/claude/nekoplay-native/design/icon/full.png').convert('RGBA').resize((640, 640), Image.LANCZOS)

def s8(lb):  # 56-64 拍：片尾
    im = base(INK)
    p = ease(seg(lb, 0, 0.6))
    paste(im, ICON, 120, 300 + (1 - p) * 500)
    t = txt('NEKOPLAY', 520, WHITE, 'lat')
    q = ease(seg(lb, 0.5, 0.6))
    paste(im, t, 860 - (1 - q) * 1200, 230)
    if lb > 1.5:
        r = ease(seg(lb, 1.5, 0.5)); paste(im, txt('原生 · 网易云音乐播放器', 160, BLUE, 'cjk'), 880, 1000 + (1 - r) * 200)
    if lb > 2.5: label(im, 'ANDROID  /  WINDOWS  /  KOTLIN COMPOSE', 885, 1300, WHITE, 64)
    if lb > 3.5: label(im, 'github.com/acnekot/NEKOPLAY', 885, 1420, (150, 150, 150), 64)
    if lb > 1: rule(im, 120, 1060, 640 * ease(seg(lb, 1, 0.6)), BLUE, 30)
    return im

SCENES = [(s1, 8), (s2, 8), (s3, 8), (s4, 8), (s5, 8), (s6, 8), (s7, 8), (s8, 8)]

def render(i, out, step=1):
    fn, beats = SCENES[i]
    n = beats * BEAT
    tmp = out + '.part.mp4'
    ff = subprocess.Popen(['ffmpeg', '-y', '-loglevel', 'error', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', f'{W}x{H}', '-r', str(FPS), '-i', '-',
                           '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '18', '-pix_fmt', 'yuv420p', '-r', str(FPS), tmp], stdin=subprocess.PIPE)
    for f in range(0, n, step):
        ff.stdin.write(fn(f / BEAT).convert('RGB').tobytes())
    ff.stdin.close(); ff.wait()
    if ff.returncode == 0: os.replace(tmp, out)

if __name__ == '__main__':
    if sys.argv[1] == 'frame':  # python render.py frame <scene> <beat> <out.png>
        SCENES[int(sys.argv[2])][0](float(sys.argv[3])).convert('RGB').save(sys.argv[4])
    else:
        render(int(sys.argv[2]), sys.argv[3])
