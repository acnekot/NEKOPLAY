#include "vst_common.h"

#if !defined(_WIN32) && !defined(__APPLE__)
void* nvGuiCreateWindow(const char*, int, int, bool, const EditorCallbacks&, void** outNativeView) {
    if (outNativeView) *outNativeView = nullptr;
    nvSetError("Linux 端暂未启用 VST 原生窗口嵌入，可直接使用内置参数滑块调节");
    return nullptr;
}
void nvGuiResizeWindow(void*, int, int) {}
void nvGuiSetOnTop(void*, bool) {}
void nvGuiDestroyWindow(void*) {}
#endif
