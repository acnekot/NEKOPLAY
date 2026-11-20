import numpy as np, subprocess
from PIL import Image, ImageDraw
W=1024
PAPER="#EEEBE3"; BLUE="#8098F0"; INK="#121317"; RED="#E5432F"
def note_svg(fill, eye):
    dx,dy=-38+20,-23
    def P(pts): return " ".join(f"{x+dx:.1f},{y+dy:.1f}" for x,y in pts)
    s=260; hx,hy=330,560; sx0,sx1=hx+s-46,hx+s
    head=P([(hx,hy),(hx+s*.34,hy+s*.32),(hx+s*.66,hy+s*.32),(hx+s,hy),(hx+s,hy+s),(hx,hy+s)])
    stem=P([(sx0,250),(sx1,250),(sx1,hy+s*.6),(sx0,hy+s*.6)])
    flag=P([(sx0,250),(sx1,250),(sx1+170,362),(sx1+170,476),(sx1,362)])
    eyes="".join(f'<rect x="{x+dx:.1f}" y="{y+dy:.1f}" width="{s*.12:.1f}" height="{s*.155:.1f}" fill="{eye}"/>' for x,y in [(hx+s*.22,hy+s*.55),(hx+s*.66,hy+s*.55)])
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{W}"><polygon points="{head}" fill="{fill}"/><polygon points="{stem}" fill="{fill}"/><polygon points="{flag}" fill="{fill}"/>{eyes}</svg>'
def render(svg, out):
    open(out+".svg","w").write(svg); subprocess.run(["rsvg-convert","-w","2048","-h","2048",out+".svg","-o",out+".png"],check=True)
    return Image.open(out+".png").convert("RGBA")
def persp(im, k=.13):
    # 透视：上边往里收（像往后仰），再顺时针转 15°
    w,h=im.size; dst=[(w*k,0),(w*(1-k),0),(w,h),(0,h)]; src=[(0,0),(w,0),(w,h),(0,h)]
    m=[]
    for (x,y),(X,Y) in zip(dst,src): m+= [[x,y,1,0,0,0,-X*x,-X*y],[0,0,0,x,y,1,-Y*x,-Y*y]]
    c=np.linalg.solve(np.array(m,float),np.array(src,float).reshape(8)).tolist()
    p=im.transform(im.size,Image.PERSPECTIVE,c,Image.BICUBIC)
    return p.rotate(-15,Image.BICUBIC,center=(w*.5,h*.53))
fg=persp(render(note_svg(INK,"#FFFFFF"),"note"))
fg.resize((W,W),Image.LANCZOS).save("fg.png")
# 中景：大色块圆 + 横向粗条 + 细线 + 红色小方块（瑞士海报式几何）
mid=f'''<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{W}">
<circle cx="600" cy="430" r="300" fill="{BLUE}"/>
<rect x="0" y="742" width="1024" height="60" fill="{INK}"/>
<rect x="128" y="160" width="250" height="6" fill="{INK}"/>
<rect x="128" y="186" width="160" height="6" fill="{INK}"/>
<rect x="800" y="742" width="60" height="60" fill="{RED}"/>
</svg>'''
render(mid,"mid").resize((W,W),Image.LANCZOS).save("mid.png")
bg=Image.new("RGBA",(W,W),PAPER); bg.save("bg.png")
full=Image.alpha_composite(Image.alpha_composite(bg,Image.open("mid.png")),Image.open("fg.png"))
full.save("full_sq.png")
m=Image.new("L",(W*4,W*4),0); ImageDraw.Draw(m).rounded_rectangle([0,0,W*4-1,W*4-1],int(W*4*.225),fill=255)
r=Image.new("RGBA",(W,W),(0,0,0,0)); r.paste(full,(0,0),m.resize((W,W),Image.LANCZOS)); r.save("full.png")
