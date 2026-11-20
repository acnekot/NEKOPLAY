import numpy as np, subprocess, sys
from PIL import Image, ImageDraw, ImageFilter
W=1024; S=2048; BG=(232,229,221,255); INK="#121317"
# 猫音符矢量：头往下加长（身子在画面外），这样怎么转都不会露出底边
s=260; hx,hy=330,560; sx0,sx1=hx+s-46,hx+s; ext=900
def P(pts): return " ".join(f"{x},{y}" for x,y in pts)
head=P([(hx,hy),(hx+s*.34,hy+s*.32),(hx+s*.66,hy+s*.32),(hx+s,hy),(hx+s,hy+s+ext),(hx,hy+s+ext)])
stem=P([(sx0,250),(sx1,250),(sx1,hy+s*.6),(sx0,hy+s*.6)])
flag=P([(sx0,250),(sx1,250),(sx1+170,362),(sx1+170,476),(sx1,362)])
eyes="".join(f'<rect x="{x}" y="{hy+s*.55}" width="{s*.12}" height="{s*.155}" fill="#FFFFFF"/>' for x in (hx+s*.22,hx+s*.66))
H=2000
svg=f'<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="{H}"><polygon points="{head}" fill="{INK}"/><polygon points="{stem}" fill="{INK}"/><polygon points="{flag}" fill="{INK}"/>{eyes}</svg>'
open("peek.svg","w").write(svg); subprocess.run(["rsvg-convert","-z","2","peek.svg","-o","peek_raw.png"],check=True)
im=Image.open("peek_raw.png").convert("RGBA"); w,h=im.size
# 轻微透视：以猫头那一段为准收窄上边
k=.04; dst=[(w*k,0),(w*(1-k),0),(w,h),(0,h)]; src=[(0,0),(w,0),(w,h),(0,h)]
m=[]
for (x,y),(X,Y) in zip(dst,src): m+=[[x,y,1,0,0,0,-X*x,-X*y],[0,0,0,x,y,1,-Y*x,-Y*y]]
c=np.linalg.solve(np.array(m,float),np.array(src,float).reshape(8)).tolist()
im=im.transform(im.size,Image.PERSPECTIVE,c,Image.BICUBIC)
sc=float(sys.argv[1]); ang=float(sys.argv[2]); ox=float(sys.argv[3]); oy=float(sys.argv[4])
im=im.resize((int(w*sc),int(h*sc)),Image.LANCZOS)
# 以眼睛中心为锚点旋转、定位
ex=(hx+s*.5)*2*sc; ey=(hy+s*.62)*2*sc
big=Image.new("RGBA",(S*3,S*3),(0,0,0,0)); big.alpha_composite(im,(int(S*1.5-ex),int(S*1.5-ey)))
big=big.rotate(-ang,Image.BICUBIC,center=(S*1.5,S*1.5))
P=float(sys.argv[5]) if len(sys.argv)>5 else 0.0   # 四周多留的比例（自适应图标用）
x0=int(S*1.5-ox*S-P*S); y0=int(S*1.5-oy*S-P*S); SZ=int(S*(1+2*P))
layer=big.crop((x0,y0,x0+SZ,y0+SZ)).resize((S,S),Image.LANCZOS)
layer.save("shape.png")
a=layer.split()[3].filter(ImageFilter.GaussianBlur(48)); sh=Image.new("RGBA",(S,S),(0,0,0,0)); sh.putalpha(a.point(lambda v:int(v*.36)))
out=Image.new("RGBA",(S,S),(0,0,0,0)); out.alpha_composite(sh,(40,80)); out.alpha_composite(layer)
fgl=out.resize((W,W),Image.LANCZOS); fgl.save("fg.png")
full=Image.alpha_composite(Image.new("RGBA",(W,W),BG),fgl)
mk=Image.new("L",(W*4,W*4),0); ImageDraw.Draw(mk).rounded_rectangle([0,0,W*4-1,W*4-1],int(W*4*.225),fill=255)
r=Image.new("RGBA",(W,W),(0,0,0,0)); r.paste(full,(0,0),mk.resize((W,W),Image.LANCZOS)); r.save("full.png")
