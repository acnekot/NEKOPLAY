# 150 BPM 合成节拍：底鼓每拍、军鼓 2/4 拍、16 分音符踩镲、低音脉冲，场景切换处加冲击音，结尾收尾
import numpy as np, wave
SR = 48000; BPM = 150; BEAT = 60 / BPM; NB = 64
N = int(SR * BEAT * NB) + SR  # 多 1 秒尾音
out = np.zeros(N)
rng = np.random.default_rng(7)

def add(sig, t):
    i = int(t * SR); out[i:i + len(sig)] += sig[:max(0, N - i)]

def kick():
    n = int(SR * .35); t = np.arange(n) / SR
    f = 50 + 120 * np.exp(-t * 30)
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 9) * .9
def snare():
    n = int(SR * .22); t = np.arange(n) / SR
    return (rng.standard_normal(n) * .5 + np.sin(2 * np.pi * 190 * t) * .4) * np.exp(-t * 22) * .55
def hat(open_=False):
    n = int(SR * (.12 if open_ else .04)); t = np.arange(n) / SR
    x = rng.standard_normal(n); x = np.diff(np.concatenate([[0], x]))  # 简单高通
    return x * np.exp(-t * (25 if open_ else 90)) * .18
def bass(freq, dur):
    n = int(SR * dur); t = np.arange(n) / SR
    s = np.sign(np.sin(2 * np.pi * freq * t)) * .5 + np.sin(2 * np.pi * freq * t) * .5
    env = np.minimum(1, t * 200) * np.exp(-t * 6)
    return s * env * .22
def impact():
    n = int(SR * 1.2); t = np.arange(n) / SR
    return (np.sin(2 * np.pi * (40 + 60 * np.exp(-t * 8)) * t) * .8 + rng.standard_normal(n) * .25 * np.exp(-t * 12)) * np.exp(-t * 3) * .8
def riser(dur):
    n = int(SR * dur); t = np.arange(n) / SR
    x = rng.standard_normal(n) * (t / dur) ** 2 * .25
    return x

notes = [55, 55, 65.4, 49]  # A1 A1 C2 G1，每两小节换一次
for b in range(NB):
    t = b * BEAT
    if b < 60 or b % 2 == 0: add(kick(), t)
    if b % 2 == 1 and b < 60: add(snare(), t)
    if 8 <= b < 60:
        for k in range(4): add(hat(k == 2), t + k * BEAT / 4)
        add(bass(notes[(b // 8) % 4], BEAT * .5), t + BEAT / 2)
    if b % 8 == 0 and b > 0: add(impact(), t)
    if b % 8 == 6 and 8 <= b < 56: add(riser(BEAT * 2), t)
# 结尾长低音
add(impact(), 56 * BEAT)
out = np.tanh(out * 1.2) * .85
st = np.stack([out, out], 1)
with wave.open('/home/claude/promo/out/beat.wav', 'wb') as w:
    w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR)
    w.writeframes((st * 32767).astype(np.int16).tobytes())
print('ok', N / SR)
