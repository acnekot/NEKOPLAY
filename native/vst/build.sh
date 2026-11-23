#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f miniaudio.h ]; then
  curl -fsSL https://raw.githubusercontent.com/mackron/miniaudio/0.11.21/miniaudio.h -o miniaudio.h
fi

OS="$(uname -s)"
if [ "$OS" = "Darwin" ]; then
  OUT="${1:-../../desktop/resources/macos/libnekoplay_vst.dylib}"
  mkdir -p "$(dirname "$OUT")"
  clang++ -std=c++17 -O2 -shared -fPIC -fobjc-arc \
    nekoplay_vst.cpp vst2_host.cpp vst3_host.cpp gui_mac.mm \
    -framework CoreAudio -framework AudioToolbox -framework CoreFoundation -framework Cocoa \
    -install_name @loader_path/libnekoplay_vst.dylib \
    -o "$OUT"
  codesign --force -s - "$OUT" 2>/dev/null || true
  echo "Built $OUT"
elif [ "$OS" = "Linux" ]; then
  OUT="${1:-../../desktop/resources/linux/libnekoplay_vst.so}"
  mkdir -p "$(dirname "$OUT")"
  g++ -std=c++17 -O2 -shared -fPIC \
    nekoplay_vst.cpp vst2_host.cpp vst3_host.cpp gui_linux.cpp \
    -lpthread -ldl -lm \
    -o "$OUT"
  echo "Built $OUT"
else
  OUT="${1:-../../desktop/resources/windows/nekoplay_vst.dll}"
  mkdir -p "$(dirname "$OUT")"
  g++ -std=c++17 -O2 -shared \
    nekoplay_vst.cpp vst2_host.cpp vst3_host.cpp gui_win.cpp \
    -lole32 -luuid -luser32 -lgdi32 \
    -static-libgcc -static-libstdc++ -Wl,-Bstatic -lwinpthread -Wl,-Bdynamic \
    -o "$OUT"
  echo "Built $OUT"
fi
