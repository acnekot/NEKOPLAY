import subprocess
from PIL import Image, ImageDraw
W=1024
INK="#0E1117"; BLUE="#1A4BE0"; CARD="#E2EDEE"
MONO="JetBrains Mono"
def note(dx,dy,k=1.0):
    def P(pts): return " ".join(f"{x*k+dx:.1f},{y*k+dy:.1f}" for x,y in pts)
    s=250; hx,hy=0,310; sx0,sx1=hx+s-48,hx+s
    head=P([(hx,hy),(hx+s*.34,hy+s*.33),(hx+s*.66,hy+s*.33),(hx+s,hy),(hx+s,hy+s),(hx,hy+s)])
    body=P([(hx,hy),(hx+s*.34,hy+s*.33),(hx+s*.62,hy+s*.33),(sx0,hy+s*.06),(sx0,0),(sx1,0),(sx1+165,112),(sx1+165,232),(sx1,120),(sx1,hy+s),(hx,hy+s)])
    eyes=[(hx+s*.2,hy+s*.56),(hx+s*.68,hy+s*.56)]
    return body,eyes,s
def layers():
    bg=f'''<g id="bg">
<rect width="{W}" height="{W}" fill="url(#gBg)"/>
<circle cx="{W-150}" cy="230" r="300" fill="url(#gWarm)"/>
<circle cx="140" cy="{W-180}" r="320" fill="url(#gCool)"/>
</g>'''
    # 中景：BenchLab 卡片（粗黑框）+ 黑底标签 + 进度条
    cx0,cy0,cx1,cy1=168,200,856,856
    mid=f'''<g id="mid">
<rect x="{cx0}" y="{cy0}" width="{cx1-cx0}" height="{cy1-cy0}" fill="{CARD}" stroke="{INK}" stroke-width="16"/>
<rect x="{cx0+44}" y="{cy0+44}" width="196" height="46" fill="{INK}"/>
<text x="{cx0+60}" y="{cy0+78}" font-family="{MONO}" font-weight="700" font-size="30" letter-spacing="3" fill="#FFFFFF">NEKO</text>
<rect x="{cx0+44}" y="{cy1-92}" width="{cx1-cx0-88}" height="12" fill="{INK}" fill-opacity=".3"/>
<rect x="{cx0+44}" y="{cy1-92}" width="{(cx1-cx0-88)*.42:.0f}" height="12" fill="{BLUE}"/>
<rect x="{cx0+44+(cx1-cx0-88)*.42-17:.0f}" y="{cy1-103}" width="34" height="34" fill="{BLUE}"/>
<rect x="{cx0}" y="{cy0+120}" width="{cx1-cx0}" height="6" fill="{INK}"/>
</g>'''
    body,eyes,s=note(0,0)
    # 前景：主色音符 + 粗黑描边（直角）
    dx,dy=300,236
    def P(pts): return pts
    fg=f'''<g id="fg" transform="translate({dx} {dy})">
<polygon points="{body}" transform="translate(18 18)" fill="{INK}"/>
<polygon points="{body}" fill="{BLUE}" stroke="{INK}" stroke-width="16" stroke-linejoin="miter" stroke-miterlimit="10"/>
{"".join(f'<rect x="{x:.1f}" y="{y:.1f}" width="{s*.12:.1f}" height="{s*.16:.1f}" fill="#FFFFFF"/>' for x,y in eyes)}
</g>'''
    return bg,mid,fg
DEFS=f'''<defs>
<linearGradient id="gBg" x1=".37" y1="0" x2=".63" y2="1"><stop offset="0" stop-color="#C6DCE4"/><stop offset=".5" stop-color="#D3E5EA"/><stop offset="1" stop-color="#E2EDEE"/></linearGradient>
<radialGradient id="gWarm"><stop offset="0" stop-color="#FFD9B0" stop-opacity=".55"/><stop offset="1" stop-color="#FFD9B0" stop-opacity="0"/></radialGradient>
<radialGradient id="gCool"><stop offset="0" stop-color="#A8C8FF" stop-opacity=".6"/><stop offset="1" stop-color="#A8C8FF" stop-opacity="0"/></radialGradient>
</defs>'''
bg,mid,fg=layers()
def out(name,*ls):
    open(name+".svg","w").write(f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{W}" viewBox="0 0 {W} {W}">{DEFS}{"".join(ls)}</svg>')
    subprocess.run(["rsvg-convert","-w",str(W),"-h",str(W),name+".svg","-o",name+".png"],check=True)
out("full_sq",bg,mid,fg); out("bg",bg); out("mid",mid); out("fg",fg); out("bgmid",bg,mid)
im=Image.open("full_sq.png").convert("RGBA")
m=Image.new("L",(W*4,W*4),0); ImageDraw.Draw(m).rounded_rectangle([0,0,W*4-1,W*4-1],int(W*4*.225),fill=255)
r=Image.new("RGBA",(W,W),(0,0,0,0)); r.paste(im,(0,0),m.resize((W,W),Image.LANCZOS)); r.save("full.png")
