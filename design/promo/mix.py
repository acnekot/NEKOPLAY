# 混剪：实机演示（demo）+ 动态排版（promo），两条用的是同一段配乐、时间轴完全对齐，按拍切换，加推拉镜头和重拍冲击缩放
import subprocess, math, sys, os
from PIL import Image
W, H, FPS, N = 3840, 2160, 120, 3600
B = 0.60375
DEMO = '/mnt/user-data/outputs/NEKOPLAY-demo-4K120.mp4'
PROMO = '/home/claude/promo2/out/v.mp4'

def ease(x): x = min(max(x, 0.0), 1.0); return x * x * (3 - 2 * x)
def lerp(a, b, k): return a + (b - a) * k

# (开始, 结束, 源, 缩放起, 缩放止, 焦点起, 焦点止)；焦点是画面里的相对坐标
SEG = [
    (0.00, 2.40, 'p', 1.00, 1.08, (.5, .5), (.5, .5)),
    (2.40, 5.43, 'd', 1.30, 1.08, (.40, .38), (.45, .45)),       # 主页 → 我喜欢 → 滚动
    (5.43, 8.15, 'p', 1.08, 1.00, (.4, .5), (.5, .5)),           # 「为听歌这件事，重新设计每一个细节。」
    (8.15, 9.90, 'd', 1.00, 1.00, (.5, .5), (.5, .5)),           # 打开播放页（全景）
    (9.90, 12.68, 'd', 1.00, 1.75, (.5, .5), (.33, .42)),        # 推近歌词旁竖栏，「译」切换
    (12.68, 15.70, 'd', 1.75, 1.40, (.62, .40), (.80, .42)),     # 播放列表展开
    (15.70, 16.90, 'p', 1.00, 1.06, (.5, .5), (.5, .5)),         # 「04 楼层评论」
    (16.90, 18.57, 'd', 1.45, 1.25, (.60, .45), (.62, .55)),     # 评论区滚动
]
# 副歌：每拍切一次，实机歌词（推近）和堆叠文字交替
t = 18.57
for k in range(7):
    SEG.append((t, t + B, 'd' if k % 2 == 0 else 'p', 1.55 if k % 2 == 0 else 1.0, 1.45 if k % 2 == 0 else 1.04, (.47, .33), (.47, .35)))
    t += B
SEG += [
    (22.80, 23.39, 'p', 1.00, 1.10, (.5, .5), (.5, .5)),         # 「Listen.」
    (23.39, 25.95, 'd', 2.10, 1.80, (.38, .30), (.40, .33)),     # and unlock the future.
    (25.95, 26.80, 'p', 1.00, 1.05, (.4, .45), (.4, .45)),       # 数字
    (26.80, 27.80, 'd', 2.20, 2.00, (.43, .26), (.43, .28)),     # Don't be afraid.
    (27.80, 30.00, 'p', 1.06, 1.00, (.5, .5), (.5, .5)),         # 片尾
]
# 重拍：切镜头时和副歌每一拍给一个短促的放大冲击
HITS = [s[0] for s in SEG[1:]] + [18.57 + k * B for k in range(7)] + [23.39 + k * B for k in range(8)]

def seg_at(t):
    for s in SEG:
        if s[0] <= t < s[1]: return s
    return SEG[-1]

def zoom(img, z, fx, fy):
    if z <= 1.001: return img
    cw, ch = W / z, H / z
    x = min(max(fx * W - cw / 2, 0), W - cw); y = min(max(fy * H - ch / 2, 0), H - ch)
    return img.resize((W, H), Image.BILINEAR, box=(x, y, x + cw, y + ch))

def reader(path):
    return subprocess.Popen(['ffmpeg', '-v', 'error', '-i', path, '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'], stdout=subprocess.PIPE, bufsize=W * H * 3 * 2)

out = sys.argv[1]
d, p = reader(DEMO), reader(PROMO)
enc = subprocess.Popen(['ffmpeg', '-y', '-v', 'error', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', f'{W}x{H}', '-r', str(FPS), '-i', '-',
                        '-i', '/home/claude/promo2/clip.wav', '-map', '0:v', '-map', '1:a', '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '17',
                        '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-b:a', '320k', '-shortest', '-movflags', '+faststart', out + '.part.mp4'], stdin=subprocess.PIPE)
sz = W * H * 3
for f in range(N):
    t = f / FPS
    bd = d.stdout.read(sz); bp = p.stdout.read(sz)
    s = seg_at(t)
    k = ease((t - s[0]) / (s[1] - s[0]))
    z = lerp(s[3], s[4], k)
    # 冲击：每个重拍放大 7%，0.12 秒衰减
    for h in HITS:
        if 0 <= t - h < .5: z *= 1 + .07 * math.exp(-(t - h) / .12)
    fx = lerp(s[5][0], s[6][0], k); fy = lerp(s[5][1], s[6][1], k)
    img = Image.frombytes('RGB', (W, H), bd if s[2] == 'd' else bp)
    enc.stdin.write(zoom(img, z, fx, fy).tobytes())
    if f % 240 == 0: print('frame', f, flush=True)
enc.stdin.close(); enc.wait()
if enc.returncode == 0: os.replace(out + '.part.mp4', out)
print('done', enc.returncode)
