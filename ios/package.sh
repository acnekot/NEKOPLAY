#!/bin/bash
# 在 macOS 上把链接好的可执行文件装成 NEKOPLAY.app，再打成未签名 IPA（用 AltStore / Sideloadly / 巨魔等自签安装）
set -euo pipefail
cd "$(dirname "$0")"
VERSION="${VERSION:-$(sed -n 's/.*APP_VERSION = "\([^"]*\)".*/\1/p' ../shared/src/cc/catsuki/nekoplay/core/Version.kt)}"; BUILD="${BUILD:-1}"
SDK=$(xcrun --sdk iphoneos --show-sdk-version)
OUT=build/ipa; APP=$OUT/Payload/NEKOPLAY.app
rm -rf "$OUT"; mkdir -p "$APP/font"
cp build/bin/iosArm64/releaseExecutable/NEKOPLAY.kexe "$APP/NEKOPLAY"
chmod +x "$APP/NEKOPLAY"
sed -e "s/@VERSION@/$VERSION/" -e "s/@BUILD@/$BUILD/" -e "s/@SDK@/$SDK/g" Info.plist > "$APP/Info.plist"
plutil -lint "$APP/Info.plist"
printf 'APPL????' > "$APP/PkgInfo"
cp ../desktop/src/main/resources/font/*.ttf "$APP/font/"
# 图标：去掉透明通道（iOS 图标不能带 alpha）
ICON=../desktop/icons/icon.png
mk() { sips -s format png -z "$2" "$2" "$ICON" --out "$APP/$1" >/dev/null; }
mk AppIcon60x60@2x.png 120; mk AppIcon60x60@3x.png 180
mk AppIcon76x76@2x~ipad.png 152; mk AppIcon83.5x83.5@2x~ipad.png 167
for f in "$APP"/AppIcon*.png; do sips -s format jpeg "$f" --out /tmp/x.jpg >/dev/null && sips -s format png /tmp/x.jpg --out "$f" >/dev/null; done
# 临时签名（ad-hoc）：自签工具会重新签；巨魔也能直接装
codesign --force --sign - --timestamp=none "$APP"
(cd "$OUT" && zip -qry NEKOPLAY-ios-unsigned.ipa Payload)
ls -la "$OUT"
