import numpy as np
from PIL import Image, ImageDraw, ImageFilter
W=1024; BG=(232,229,221,255)
def coeffs(src,dst):
    m=[]
    for (x,y),(X,Y) in zip(dst,src): m+=[[x,y,1,0,0,0,-X*x,-X*y],[0,0,0,x,y,1,-Y*x,-Y*y]]
    return np.linalg.solve(np.array(m,float),np.array(src,float).reshape(8)).tolist()
# 前景：瑞士版的黑色猫音符（已顺时针 15° + 透视），再按展示图那样放平成等距视角
fg=Image.open('/tmp/ic3/fg.png').convert("RGBA").resize((2048,2048),Image.LANCZOS)
S=2048; C=S/2
dst=[(C-0.5*S*1.0,C),(C,C-0.29*S),(C+0.5*S*1.0,C),(C,C+0.29*S)]
iso=fg.transform((S,S),Image.PERSPECTIVE,coeffs([(0,0),(S,0),(S,S),(0,S)],dst),Image.BICUBIC)
bb=iso.getbbox(); cw,ch=bb[2]-bb[0],bb[3]-bb[1]
crop=iso.crop(bb)
k=min(0.62*S/cw,0.62*S/ch); crop=crop.resize((int(cw*k),int(ch*k)),Image.LANCZOS)
layer=Image.new("RGBA",(S,S),(0,0,0,0)); px=(S-crop.width)//2; py=(S-crop.height)//2-int(.03*S)
layer.alpha_composite(crop,(px,py))
# 柔和投影：往下偏、模糊
a=layer.split()[3].filter(ImageFilter.GaussianBlur(44))
sh=Image.new("RGBA",(S,S),(0,0,0,0)); sh.putalpha(a.point(lambda v:int(v*.38)))
fgl=Image.new("RGBA",(S,S),(0,0,0,0)); fgl.alpha_composite(sh,(26,90)); fgl.alpha_composite(layer)
fgl=fgl.resize((W,W),Image.LANCZOS); fgl.save("fg.png")
bg=Image.new("RGBA",(W,W),BG); bg.save("bg.png")
full=Image.alpha_composite(bg,fgl); full.save("full_sq.png")
mk=Image.new("L",(W*4,W*4),0); ImageDraw.Draw(mk).rounded_rectangle([0,0,W*4-1,W*4-1],int(W*4*.225),fill=255)
r=Image.new("RGBA",(W,W),(0,0,0,0)); r.paste(full,(0,0),mk.resize((W,W),Image.LANCZOS)); r.save("full.png")
