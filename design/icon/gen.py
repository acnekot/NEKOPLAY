from PIL import Image, ImageDraw, ImageFilter
W=1024; BG=(232,229,221,255)
# 主体：瑞士版的黑色猫音符（顺时针 15° + 透视），不放平
fg=Image.open('/tmp/ic3/fg.png').convert("RGBA").resize((2048,2048),Image.LANCZOS); S=2048
bb=fg.getbbox(); crop=fg.crop(bb); k=min(.6*S/crop.width,.62*S/crop.height); crop=crop.resize((int(crop.width*k),int(crop.height*k)),Image.LANCZOS)
layer=Image.new("RGBA",(S,S),(0,0,0,0)); layer.alpha_composite(crop,((S-crop.width)//2-int(.01*S),(S-crop.height)//2-int(.02*S)))
# 柔和投影：往右下偏、大半径模糊
a=layer.split()[3].filter(ImageFilter.GaussianBlur(48))
sh=Image.new("RGBA",(S,S),(0,0,0,0)); sh.putalpha(a.point(lambda v:int(v*.36)))
out=Image.new("RGBA",(S,S),(0,0,0,0)); out.alpha_composite(sh,(40,80)); out.alpha_composite(layer)
fgl=out.resize((W,W),Image.LANCZOS); fgl.save("fg.png")
Image.new("RGBA",(W,W),BG).save("bg.png")
full=Image.alpha_composite(Image.open("bg.png"),fgl); full.save("full_sq.png")
mk=Image.new("L",(W*4,W*4),0); ImageDraw.Draw(mk).rounded_rectangle([0,0,W*4-1,W*4-1],int(W*4*.225),fill=255)
r=Image.new("RGBA",(W,W),(0,0,0,0)); r.paste(full,(0,0),mk.resize((W,W),Image.LANCZOS)); r.save("full.png")
