import math
W=1024
def note(dx=-38,dy=-23):
    def P(pts): return " ".join(f"{x+dx:.1f},{y+dy:.1f}" for x,y in pts)
    s=260; hx,hy=330,560
    head=P([(hx,hy),(hx+s*.34,hy+s*.32),(hx+s*.66,hy+s*.32),(hx+s,hy),(hx+s,hy+s),(hx,hy+s)])
    sx0,sx1=hx+s-46,hx+s
    stem=P([(sx0,250),(sx1,250),(sx1,hy+s*.6),(sx0,hy+s*.6)])
    flag=P([(sx0,250),(sx1,250),(sx1+170,362),(sx1+170,476),(sx1,362)])
    ex=[(hx+s*.22,hy+s*.55),(hx+s*.78-s*.12,hy+s*.55)]
    eyes="".join(f'<rect x="{x+dx:.1f}" y="{y+dy:.1f}" width="{s*.12:.1f}" height="{s*.155:.1f}" fill="#1A2150"/>' for x,y in ex)
    return head,stem,flag,eyes,(hx+s/2+dx, hy+s*.6+dy)
head,stem,flag,eyes,(cx,cy)=note()

def layer_bg(clip=True):
    rings="".join(f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="none" stroke="#FFFFFF" stroke-opacity="{0.16*(1-i/9):.3f}" stroke-width="{2 if i%3==0 else 1}"/>' for i,r in enumerate(range(150,1100,105)))
    grid="".join(f'<line x1="{x}" y1="0" x2="{x}" y2="{W}"/>' for x in range(0,W+1,64))+"".join(f'<line x1="0" y1="{y}" x2="{W}" y2="{y}"/>' for y in range(0,W+1,64))
    ticks="".join(f'<rect x="{x-3}" y="{y-3}" width="6" height="6"/>' for x in range(128,W,256) for y in range(128,W,256))
    return f'''<g id="bg">
<rect width="{W}" height="{W}" fill="url(#gBg)"/>
<rect width="{W}" height="{W}" fill="url(#gGlow)"/>
<g stroke="#FFFFFF" stroke-opacity=".07" stroke-width="1" mask="url(#mFade)">{grid}</g>
<g fill="#FFFFFF" fill-opacity=".22" mask="url(#mFade)">{ticks}</g>
<g mask="url(#mFade)">{rings}</g>
</g>'''

def layer_mid():
    # 玻璃卡片 + 频谱 + 轨道
    bars=""
    import random; random.seed(7)
    n=26; x0=250; w=524; bw=w/n
    for i in range(n):
        h=40+ (math.sin(i*.55)*.5+.5)*120*(0.6+0.4*random.random())
        x=x0+i*bw
        bars+=f'<rect x="{x+bw*.25:.1f}" y="{820-h:.1f}" width="{bw*.5:.1f}" height="{h:.1f}" fill="#FFFFFF" fill-opacity="{0.10+0.18*(h/200):.2f}"/>'
    return f'''<g id="mid">
<g transform="rotate(-8 512 512)">
  <rect x="222" y="222" width="580" height="580" rx="44" fill="url(#gGlass)" stroke="#FFFFFF" stroke-opacity=".32" stroke-width="2"/>
  <rect x="222" y="222" width="580" height="580" rx="44" fill="none" stroke="url(#gEdge)" stroke-width="2"/>
  <g mask="url(#mCard)">{bars}</g>
  <line x1="262" y1="270" x2="400" y2="270" stroke="#FFFFFF" stroke-opacity=".5" stroke-width="4"/>
  <line x1="262" y1="290" x2="340" y2="290" stroke="#FFFFFF" stroke-opacity=".25" stroke-width="4"/>
  <circle cx="752" cy="272" r="7" fill="#9FF3E6"/>
</g>
<ellipse cx="{cx}" cy="{cy-40}" rx="410" ry="120" fill="none" stroke="#9FF3E6" stroke-opacity=".55" stroke-width="2.5" transform="rotate(-18 {cx} {cy-40})" stroke-dasharray="2 10" stroke-linecap="round"/>
<circle cx="{cx+380}" cy="{cy-210}" r="9" fill="#9FF3E6"/>
<circle cx="{cx+380}" cy="{cy-210}" r="20" fill="#9FF3E6" fill-opacity=".18"/>
</g>'''

def layer_fg():
    return f'''<g id="fg">
<g filter="url(#fShadow)">
  <polygon points="{head}" fill="url(#gNote)"/><polygon points="{stem}" fill="url(#gNote)"/><polygon points="{flag}" fill="url(#gNote)"/>
</g>
{eyes}
</g>'''

DEFS=f'''<defs>
<linearGradient id="gBg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#93A8FF"/><stop offset=".55" stop-color="#5F72DC"/><stop offset="1" stop-color="#232B66"/></linearGradient>
<radialGradient id="gGlow" cx=".3" cy=".22" r=".7"><stop offset="0" stop-color="#FFFFFF" stop-opacity=".35"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></radialGradient>
<radialGradient id="gFadeR" cx="{cx/W:.3f}" cy="{cy/W:.3f}" r=".75"><stop offset="0" stop-color="#fff"/><stop offset=".55" stop-color="#fff" stop-opacity=".7"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient>
<mask id="mFade"><rect width="{W}" height="{W}" fill="url(#gFadeR)"/></mask>
<linearGradient id="gGlass" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#FFFFFF" stop-opacity=".26"/><stop offset="1" stop-color="#FFFFFF" stop-opacity=".06"/></linearGradient>
<linearGradient id="gEdge" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#FFFFFF" stop-opacity=".9"/><stop offset=".4" stop-color="#FFFFFF" stop-opacity="0"/></linearGradient>
<mask id="mCard"><rect x="222" y="222" width="580" height="580" rx="44" fill="#fff"/></mask>
<linearGradient id="gNote" gradientUnits="userSpaceOnUse" x1="0" y1="220" x2="0" y2="800"><stop offset="0" stop-color="#FFFFFF"/><stop offset="1" stop-color="#DCE3FF"/></linearGradient>
<linearGradient id="gTop" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#fff"/><stop offset=".5" stop-color="#000"/></linearGradient>
<mask id="mTop"><rect width="{W}" height="{W}" fill="url(#gTop)"/></mask>
<filter id="fShadow" x="-30%" y="-30%" width="160%" height="160%">
  <feGaussianBlur in="SourceAlpha" stdDeviation="22"/><feOffset dx="18" dy="26"/><feComponentTransfer><feFuncA type="linear" slope=".55"/></feComponentTransfer>
  <feFlood flood-color="#141A40"/><feComposite operator="in" in2="SourceAlpha" result="x"/>
  <feGaussianBlur in="SourceAlpha" stdDeviation="22" result="b"/><feOffset in="b" dx="18" dy="26" result="o"/>
  <feFlood flood-color="#141A40" flood-opacity=".55"/><feComposite in2="o" operator="in" result="s"/>
  <feMerge><feMergeNode in="s"/><feMergeNode in="SourceGraphic"/></feMerge>
</filter>
</defs>'''
def svg(*layers, round_=False, bgnone=False):
    clip = f'<clipPath id="cR"><rect width="{W}" height="{W}" rx="{W*.225}"/></clipPath>' if round_ else ""
    body="".join(layers)
    if round_: body=f'<g clip-path="url(#cR)">{body}</g>'
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{W}" viewBox="0 0 {W} {W}">{DEFS.replace("</defs>", clip+"</defs>")}{body}</svg>'
open("full.svg","w").write(svg(layer_bg(),layer_mid(),layer_fg(),round_=True))
open("full_sq.svg","w").write(svg(layer_bg(),layer_mid(),layer_fg()))
open("bg.svg","w").write(svg(layer_bg()))
open("bgmid.svg","w").write(svg(layer_bg(),layer_mid()))
open("mid.svg","w").write(svg(layer_mid()))
open("fg.svg","w").write(svg(layer_fg()))
