import subprocess
from PIL import Image, ImageDraw
W=1024; BG="#171717"; PLATE="#B9B2A8"; PINK="#F4C7C3"
P0,P1=192,832; L=P1-P0
# 原来猫音符的造型：方头、两只尖耳（中间 V 形凹口）、右边的符杆、斜着的符尾、方眼
sx1=788; sw=58; sx0=sx1-sw
hw=470; hh=300; hx=sx1-hw; hy=P1-hh; d=hh*.34
fl=150
pts=[(hx,hy),(hx+hw*.30,hy+d),(hx+hw*.62,hy+d),(sx0,hy),(sx0,P0),(sx1,P0),(sx1+fl,P0+fl*.62),(sx1+fl,P0+fl*.62+92),(sx1,P0+92),(sx1,P1),(hx,P1)]
poly=" ".join(f"{x:.0f},{y:.0f}" for x,y in pts)
ew,eh=hw*.085,hh*.17
eyes="".join(f'<rect x="{x:.0f}" y="{hy+hh*.52:.0f}" width="{ew:.0f}" height="{eh:.0f}" fill="{PLATE}"/>' for x in (hx+hw*.24, hx+hw*.60))
r=10
svg=f'''<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{W}"><rect width="{W}" height="{W}" fill="{BG}"/>
<rect x="{P0}" y="{P0}" width="{L}" height="{L}" rx="{r}" fill="{PLATE}"/>
<polygon points="{poly}" fill="{PINK}"/>{eyes}</svg>'''
open("icon.svg","w").write(svg); subprocess.run(["rsvg-convert","-w",str(W),"-h",str(W),"icon.svg","-o","full_sq.png"],check=True)
# 分层：前景（剪影）单独一份
fsvg=svg.replace(f'<rect width="{W}" height="{W}" fill="{BG}"/>','').replace(f'<rect x="{P0}" y="{P0}" width="{L}" height="{L}" rx="{r}" fill="{PLATE}"/>','')
open("fg.svg","w").write(fsvg); subprocess.run(["rsvg-convert","-w",str(W),"-h",str(W),"fg.svg","-o","fg_raw.png"],check=True)
im=Image.open("full_sq.png").convert("RGBA")
mk=Image.new("L",(W*4,W*4),0); ImageDraw.Draw(mk).rounded_rectangle([0,0,W*4-1,W*4-1],int(W*4*.225),fill=255)
o=Image.new("RGBA",(W,W),(0,0,0,0)); o.paste(im,(0,0),mk.resize((W,W),Image.LANCZOS)); o.save("full.png")
