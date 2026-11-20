# NEKOPLAY 宣传片 v2：OpenAI 发布片式的克制和留白 + 瑞士网格 + 动态排版
# 配乐：Lament Rain 87.66s–117.66s（约 99.4 BPM，一拍 0.6037s）。前半段间奏安静，18.57s 进副歌，22.8s 有一拍空白
import sys, os, math, subprocess
from functools import lru_cache
from PIL import Image, ImageDraw, ImageFont, ImageFilter

W, H, FPS = 3840, 2160, 120
DUR = 30.0
B = 0.60375                     # 一拍
def bt(k): return k * B          # 第 k 拍的时间
UP = '/mnt/user-data/uploads/'
BLACK = (8, 8, 10); WHITE = (245, 245, 243); GREY = (140, 142, 148); DIM = (70, 72, 78); BLUE = (79, 125, 247); PAPER = (236, 233, 225); INK = (14, 14, 14)

F = {'disp': '/usr/share/fonts/opentype/inter/InterDisplay-SemiBold.otf', 'dispm': '/usr/share/fonts/opentype/inter/InterDisplay-Medium.otf',
     'black': '/usr/share/fonts/opentype/inter/InterDisplay-Black.otf', 'mono': '/root/.fonts/mono_700.ttf',
     'cjk': '/usr/share/fonts/opentype/noto/NotoSansCJK-Medium.ttc', 'cjkb': '/usr/share/fonts/opentype/noto/NotoSansCJK-Black.ttc'}

@lru_cache(None)
def font(kind, size):
    return ImageFont.truetype(F[kind], size, index=2) if kind.startswith('cjk') else ImageFont.truetype(F[kind], size)

@lru_cache(None)
def txt(s, size, color=WHITE, kind='disp', blur=0, outline=0):
    """文字精灵；blur>0 时是预先模糊好的版本（淡入时由糊到清）"""
    if kind == 'auto': kind = 'disp' if s.isascii() else 'cjk'
    if kind == 'autob': kind = 'black' if s.isascii() else 'cjkb'
    f = font(kind, size); pad = blur * 3 + outline + 4
    l, t, r, b = f.getbbox(s, stroke_width=outline); asc, desc = f.getmetrics()
    im = Image.new('RGBA', (r - l + pad * 2, asc + desc + pad * 2))
    d = ImageDraw.Draw(im)
    if outline: d.text((pad - l, pad), s, font=f, fill=(0, 0, 0, 0), stroke_width=outline, stroke_fill=color + (255,))
    else: d.text((pad - l, pad), s, font=f, fill=color + (255,))
    if blur: im = im.filter(ImageFilter.GaussianBlur(blur))
    im.info['pad'] = pad
    return im

def fade(spr, a):
    if a >= 1: return spr
    s = spr.copy(); s.putalpha(spr.getchannel('A').point(lambda v: int(v * a))); return s

def ease(x, p=4):  # 出：先快后慢（OpenAI 那种顺滑的停）
    x = min(max(x, 0.0), 1.0); return 1 - (1 - x) ** p
def smooth(x):
    x = min(max(x, 0.0), 1.0); return x * x * (3 - 2 * x)
def prog(t, a, d): return (t - a) / d

def reveal(im, s, size, x, y, t, a, d=0.9, color=WHITE, kind='auto', rise=60, out=None, anchor='l'):
    """文字淡入：透明→不透明、从下往上 rise、由糊变清；out=(开始, 时长) 时淡出"""
    p = ease(prog(t, a, d))
    if p <= 0: return 0
    q = 1.0
    if out: q = 1 - smooth(prog(t, out[0], out[1]))
    if q <= 0: return 0
    blur = 0 if p > .85 else (6 if p > .5 else 14)
    spr = txt(s, size, color, kind, blur)
    pad = spr.info.get('pad', 0)
    xx = x - pad - (spr.width - 2 * pad if anchor == 'r' else (spr.width - 2 * pad) / 2 if anchor == 'c' else 0)
    im.alpha_composite(fade(spr, p * q), (int(xx), int(y - pad + (1 - p) * rise)))
    return spr.width - 2 * pad

# ---------- 背景：缓慢漂移的柔光（预先在小图上画好再放大，每帧只裁切） ----------
def make_glow(c1, c2):
    g = Image.new('RGB', (600, 340), BLACK); d = ImageDraw.Draw(g)
    d.ellipse([60, 120, 330, 330], fill=c1); d.ellipse([300, 20, 560, 230], fill=c2)
    g = g.filter(ImageFilter.GaussianBlur(70))
    return g.resize((W + 800, H + 500), Image.BICUBIC).convert('RGBA')
GLOW = make_glow((22, 40, 110), (40, 30, 70))
def glow_bg(t, k=1.0):
    x = int(400 + math.sin(t * .35) * 300); y = int(250 + math.cos(t * .27) * 200)
    g = GLOW.crop((x, y, x + W, y + H))
    if k < 1: g = Image.blend(Image.new('RGBA', (W, H), BLACK + (255,)), g, k)
    return g

# ---------- 截图卡片：细边框 + 柔和投影 ----------
@lru_cache(None)
def shot(name, w, crop=None):
    im = Image.open(UP + name + '-image.png').convert('RGB')
    if crop: im = im.crop(crop)
    h = round(im.height * w / im.width)
    return im.resize((w, h), Image.LANCZOS).convert('RGBA')

@lru_cache(None)
def shadow(w, h):
    s = Image.new('RGBA', (w + 400, h + 400)); ImageDraw.Draw(s).rectangle([200, 240, 200 + w, 240 + h], fill=(0, 0, 0, 170))
    return s.filter(ImageFilter.GaussianBlur(60))

def card(im, name, w, cx, cy, a=1.0, crop=None, scale=1.0):
    c = shot(name, int(w), crop)
    if abs(scale - 1) > .002: c = c.resize((int(c.width * scale), int(c.height * scale)), Image.BILINEAR)
    sh = shadow(c.width // 8 * 8, c.height // 8 * 8)
    x, y = int(cx - c.width / 2), int(cy - c.height / 2)
    im.alpha_composite(fade(sh, a), (x - 200, y - 200))
    im.alpha_composite(fade(c, a), (x, y))
    ImageDraw.Draw(im).rectangle([x, y, x + c.width - 1, y + c.height - 1], outline=(255, 255, 255, int(40 * a)), width=3)
    return x, y, c.width, c.height

def mono(im, s, x, y, color=GREY, size=44, a=1.0):
    im.alpha_composite(fade(txt(s, size, color, 'mono' if s.isascii() else 'cjk'), a), (int(x), int(y)))

def hud(im, idx, title, a=1.0, color=GREY):
    mono(im, f'{idx:02d}', 160, 110, color, a=a); mono(im, title, 320, 110, color, a=a)
    mono(im, 'NEKOPLAY', W - 520, 110, color, a=a)

ICON = Image.open('/home/claude/nekoplay-native/design/icon/full.png').convert('RGBA')
@lru_cache(None)
def icon(sz): return ICON.resize((sz, sz), Image.LANCZOS)

# ======================= 时间线 =======================
def A(t):  # 0–14 拍：间奏。黑底柔光，一句一句淡入，像发布会开场
    im = glow_bg(t, min(1, t / 2))
    hud(im, 0, 'INTRODUCING', ease(prog(t, bt(1), 1)))
    out1 = (bt(6.5), .5)
    reveal(im, 'NEKOPLAY', 300, W / 2, H / 2 - 220, t, bt(1), 1.2, kind='disp', anchor='c', out=out1)
    reveal(im, '一个原生的网易云音乐播放器', 110, W / 2, H / 2 + 140, t, bt(3), 1.0, color=GREY, anchor='c', out=out1)
    out2 = (bt(13.3), .4)
    reveal(im, '为听歌这件事，', 170, 320, H / 2 - 260, t, bt(7.5), .9, out=out2)
    reveal(im, '重新设计每一个细节。', 170, 320, H / 2 - 30, t, bt(9.5), .9, out=out2)
    reveal(im, 'Android  ·  Windows  ·  Kotlin Compose', 64, 330, H / 2 + 260, t, bt(11.5), .8, color=GREY, kind='mono', out=out2)
    return im

FEAT_B = [('逐字歌词', 'Karaoke lyrics, line by line.', 'cc217e49', None),
          ('翻译与当前句放大', 'Translation. Focus on the line you hear.', 'cc217e49', (1100, 230, 2240, 1400)),
          ('播放队列', 'Your queue, always one click away.', '73060ab8', None),
          ('楼层评论', 'Every reply, in its thread.', '05bbe968', None)]

def Bsec(t):  # 14–31 拍：渐强。大截图缓慢推近，左侧说明每 4 拍换一句
    lt = t - bt(14)
    im = glow_bg(t)
    i = min(int(lt / bt(4)), 3); a0 = bt(14 + 4 * i)
    cn, en, name, crop = FEAT_B[i]
    hud(im, i + 1, 'DESIGN', 1)
    pin = ease(prog(t, a0, .8))
    zoom = 1 + (t - a0) * .012
    card(im, name, 2350 if crop is None else 1400, W - 1250, H / 2 + 60 + (1 - pin) * 120, pin, crop, zoom)
    reveal(im, f'0{i + 1}', 64, 320, 520, t, a0, .6, color=BLUE, kind='mono')
    reveal(im, cn, 150, 320, 640, t, a0 + .1, .8)
    reveal(im, en, 64, 324, 880, t, a0 + .35, .8, color=GREY, kind='dispm')
    # 第 30 拍：画面收成一条线，迎接副歌
    if t > bt(30):
        q = ease(prog(t, bt(30), B), 3)
        d = ImageDraw.Draw(im)
        d.rectangle([0, 0, W, int(H / 2 * q)], fill=BLACK + (255,)); d.rectangle([0, int(H - H / 2 * q), W, H], fill=BLACK + (255,))
    return im

DROP = ['逐字歌词', '无缝播放', '淡入淡出', '桌面歌词', '侧拉模式', '莫奈取色', '本地下载', '楼层评论', 'WebDAV', '听歌统计', '内置插件', '按钮布局', '真实音质', '一套代码']
DROP_SHOTS = ['cc217e49', '8786c037', '05bbe968', '73060ab8', 'be2d8b0b', 'c0b6a9b5', 'f692ce55', '71f70856']

def C(t):  # 31–38 拍：副歌。半拍一个词，截图一张张叠上来（快 + 堆叠）
    lt = t - bt(31); hb = B / 2
    k = int(lt / hb)
    im = Image.new('RGBA', (W, H), BLACK + (255,))
    # 截图逐拍叠成一摞（每拍一张），斜向错位
    n = min(int(lt / B) + 1, len(DROP_SHOTS))
    for j in range(n):
        p = ease(prog(lt, j * B, .35), 5)
        c = shot(DROP_SHOTS[j], 1500)
        x = 1900 + j * 150; y = 360 + j * 110 + (1 - p) * 900
        im.alpha_composite(fade(c, p), (int(x), int(y)))
        ImageDraw.Draw(im).rectangle([x, y, x + c.width, y + c.height], outline=(255, 255, 255, int(60 * p)), width=3)
    # 左侧大词：当前词大，之前的词缩小往上叠
    for j in range(max(0, k - 5), k + 1):
        w = DROP[j % len(DROP)]
        if j == k:
            p = ease(prog(lt, k * hb, .18), 5)
            spr = txt(w, 300, WHITE, 'autob')
            im.alpha_composite(spr, (int(140 - (1 - p) * 500), 1300))
        else:
            age = k - j
            im.alpha_composite(txt(w, 110, (90 + 30 * (5 - age), 92 + 30 * (5 - age), 100 + 30 * (5 - age)), 'auto'), (160, 1330 - age * 150))
    mono(im, f'{k + 1:02d} / {len(DROP)}', 160, 110, BLUE)
    ImageDraw.Draw(im).rectangle([160, H - 160, 160 + (W - 320) * min(1, (lt / (7 * B))), H - 148], fill=BLUE + (255,))
    return im

def D(t):  # 38–39 拍：音乐空一拍 → 全黑只留一个词
    im = Image.new('RGBA', (W, H), BLACK + (255,))
    spr = txt('Listen.', 220, WHITE, 'disp')
    a = ease(prog(t, bt(38), .25))
    im.alpha_composite(fade(spr, a), (int(W / 2 - spr.width / 2), int(H / 2 - spr.height / 2)))
    return im

STATS = [('174', 'fps', '显卡渲染，跑满刷新率'), ('9', 'plugins', '内置插件，随开随关'), ('2', 'platforms', '手机和电脑，一套代码'), ('FLAC', '48k / 24bit', '真实音质，直接显示')]

def E(t):  # 39–46 拍：纸色瑞士网格，数字砸屏 + 堆叠
    lt = t - bt(39)
    im = Image.new('RGBA', (W, H), PAPER + (255,))
    d = ImageDraw.Draw(im)
    for i in range(1, 12): d.line([(i * W / 12, 0), (i * W / 12, H)], fill=INK + (28,), width=2)
    for j in range(1, 6): d.line([(0, j * H / 6), (W, j * H / 6)], fill=INK + (28,), width=2)
    i = min(int(lt / (B * 1.75)), 3)
    a0 = i * B * 1.75
    p = ease(prog(lt, a0, .3), 5)
    n, unit, cap = STATS[i]
    big = txt(n, 1000 if len(n) < 4 else 760, BLUE, 'black')
    im.alpha_composite(big, (int(120), int(300 + (1 - p) * 700)))
    im.alpha_composite(txt(unit, 180, INK, 'disp'), (int(160 + big.width), int(900 - (1 - p) * 300)))
    im.alpha_composite(txt(cap, 120, INK, 'cjk'), (160, 1600))
    # 之前的数字缩小叠在右上
    for j in range(i):
        im.alpha_composite(txt(STATS[j][0] + ' ' + STATS[j][1], 110, (120, 120, 120), 'disp'), (W - 1050, 300 + j * 150))
    mono(im, 'MEASURED ON WINDOWS · 2026', 160, 110, INK)
    return im

def Fend(t):  # 46–50 拍：片尾，安静收尾
    lt = t - bt(46)
    im = glow_bg(t, 1 - min(1, max(0, (t - 28.6) / 1.2)))
    ic = icon(520)
    p = ease(prog(lt, 0, .9))
    im.alpha_composite(fade(ic, p), (int(W / 2 - 260), int(560 + (1 - p) * 60)))
    reveal(im, 'NEKOPLAY', 200, W / 2, 1180, t, bt(46.5), .9, anchor='c', kind='disp')
    reveal(im, '原生 · 网易云音乐播放器', 80, W / 2, 1460, t, bt(47.5), .9, color=GREY, anchor='c')
    reveal(im, 'github.com/acnekot/NEKOPLAY', 52, W / 2, 1620, t, bt(48.3), .9, color=DIM, anchor='c', kind='mono')
    if t > 28.8:  # 最后整体淡到黑
        k = smooth((t - 28.8) / 1.2)
        im = Image.blend(im, Image.new('RGBA', (W, H), BLACK + (255,)), k)
    return im

def frame(t):
    if t < bt(14): return A(t)
    if t < bt(31): return Bsec(t)
    if t < bt(38): return C(t)
    if t < bt(39): return D(t)
    if t < bt(46): return E(t)
    return Fend(t)

def render(f0, f1, out):
    tmp = out + '.part.mp4'
    ff = subprocess.Popen(['ffmpeg', '-y', '-loglevel', 'error', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', f'{W}x{H}', '-r', str(FPS), '-i', '-',
                           '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '17', '-pix_fmt', 'yuv420p', '-r', str(FPS), tmp], stdin=subprocess.PIPE)
    for f in range(f0, f1): ff.stdin.write(frame(f / FPS).convert('RGB').tobytes())
    ff.stdin.close(); ff.wait()
    if ff.returncode == 0: os.replace(tmp, out)

if __name__ == '__main__':
    if sys.argv[1] == 'frame':
        frame(float(sys.argv[2])).convert('RGB').save(sys.argv[3])
    else:  # chunk i of n
        i, n = int(sys.argv[2]), int(sys.argv[3]); tot = int(DUR * FPS)
        render(tot * i // n, tot * (i + 1) // n, sys.argv[4])
