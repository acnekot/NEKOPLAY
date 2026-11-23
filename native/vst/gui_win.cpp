#include "vst_common.h"

#ifdef _WIN32
#include <windows.h>
#include <objbase.h>
#include <condition_variable>
#include <thread>

namespace {

constexpr UINT WM_NV_CREATE = WM_APP + 101;
constexpr UINT WM_NV_RESIZE = WM_APP + 102;
constexpr UINT WM_NV_ONTOP  = WM_APP + 103;
constexpr UINT WM_NV_DESTROY = WM_APP + 104;
constexpr UINT_PTR IDLE_TIMER_ID = 1;

struct WinHolder {
    HWND hwnd = nullptr;
    EditorCallbacks cb;
    bool closed = false;
};

struct CreateReq {
    std::string title;
    int w = 640;
    int h = 420;
    bool onTop = false;
    EditorCallbacks cb;
    WinHolder* result = nullptr;
    std::mutex mtx;
    std::condition_variable cv;
    bool done = false;
};

static std::atomic<DWORD> gGuiThreadId{0};
static std::once_flag gGuiOnce;

LRESULT CALLBACK nvWndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    auto* h = reinterpret_cast<WinHolder*>(GetWindowLongPtrW(hwnd, GWLP_USERDATA));
    switch (msg) {
        case WM_TIMER:
            if (wp == IDLE_TIMER_ID && h && !h->closed && h->cb.onIdle) {
                h->cb.onIdle(h->cb.userData);
            }
            return 0;
        case WM_CLOSE:
            if (h && !h->closed) {
                h->closed = true;
                KillTimer(hwnd, IDLE_TIMER_ID);
                if (h->cb.onClose) h->cb.onClose(h->cb.userData);
            }
            DestroyWindow(hwnd);
            return 0;
        case WM_DESTROY:
            if (h) {
                SetWindowLongPtrW(hwnd, GWLP_USERDATA, 0);
                delete h;
            }
            return 0;
        default:
            return DefWindowProcW(hwnd, msg, wp, lp);
    }
}

void ensureGuiThread() {
    std::call_once(gGuiOnce, []() {
        std::mutex m;
        std::condition_variable cv;
        bool ready = false;
        std::thread([&]() {
            CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
            WNDCLASSEXW wc{};
            wc.cbSize = sizeof(wc);
            wc.style = CS_HREDRAW | CS_VREDRAW;
            wc.lpfnWndProc = nvWndProc;
            wc.hInstance = GetModuleHandleW(nullptr);
            wc.hCursor = LoadCursor(nullptr, IDC_ARROW);
            wc.hbrBackground = reinterpret_cast<HBRUSH>(COLOR_WINDOW + 1);
            wc.lpszClassName = L"NEKOPLAY_VST_EDITOR";
            RegisterClassExW(&wc);

            MSG msg{};
            PeekMessageW(&msg, nullptr, WM_USER, WM_USER, PM_NOREMOVE);
            gGuiThreadId.store(GetCurrentThreadId());
            {
                std::lock_guard<std::mutex> lk(m);
                ready = true;
            }
            cv.notify_one();

            while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
                if (msg.hwnd == nullptr) {
                    if (msg.message == WM_NV_CREATE) {
                        auto* req = reinterpret_cast<CreateReq*>(msg.lParam);
                        int wlen = MultiByteToWideChar(CP_UTF8, 0, req->title.c_str(), -1, nullptr, 0);
                        std::wstring wtitle(static_cast<size_t>(wlen), L'\0');
                        MultiByteToWideChar(CP_UTF8, 0, req->title.c_str(), -1, wtitle.data(), wlen);
                        RECT rc{0, 0, req->w, req->h};
                        DWORD style = WS_OVERLAPPEDWINDOW;
                        DWORD exStyle = req->onTop ? WS_EX_TOPMOST : 0;
                        AdjustWindowRectEx(&rc, style, FALSE, exStyle);
                        HWND hwnd = CreateWindowExW(exStyle, L"NEKOPLAY_VST_EDITOR", wtitle.c_str(), style,
                                                    CW_USEDEFAULT, CW_USEDEFAULT, rc.right - rc.left, rc.bottom - rc.top,
                                                    nullptr, nullptr, GetModuleHandleW(nullptr), nullptr);
                        if (hwnd) {
                            auto* holder = new WinHolder();
                            holder->hwnd = hwnd;
                            holder->cb = req->cb;
                            SetWindowLongPtrW(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(holder));
                            if (req->cb.onAttach && !req->cb.onAttach(req->cb.userData, static_cast<void*>(hwnd))) {
                                holder->closed = true;
                                DestroyWindow(hwnd);
                            } else {
                                if (req->cb.onIdle) SetTimer(hwnd, IDLE_TIMER_ID, 33, nullptr);
                                ShowWindow(hwnd, SW_SHOW);
                                UpdateWindow(hwnd);
                                req->result = holder;
                            }
                        }
                        {
                            std::lock_guard<std::mutex> lk(req->mtx);
                            req->done = true;
                        }
                        req->cv.notify_one();
                        continue;
                    } else if (msg.message == WM_NV_RESIZE) {
                        auto* h = reinterpret_cast<WinHolder*>(msg.wParam);
                        int w = LOWORD(msg.lParam), ht = HIWORD(msg.lParam);
                        if (h && h->hwnd && IsWindow(h->hwnd)) {
                            RECT rc{0, 0, w, ht};
                            DWORD style = static_cast<DWORD>(GetWindowLongW(h->hwnd, GWL_STYLE));
                            DWORD exStyle = static_cast<DWORD>(GetWindowLongW(h->hwnd, GWL_EXSTYLE));
                            AdjustWindowRectEx(&rc, style, FALSE, exStyle);
                            SetWindowPos(h->hwnd, nullptr, 0, 0, rc.right - rc.left, rc.bottom - rc.top, SWP_NOMOVE | SWP_NOZORDER);
                        }
                        continue;
                    } else if (msg.message == WM_NV_ONTOP) {
                        auto* h = reinterpret_cast<WinHolder*>(msg.wParam);
                        bool onTop = msg.lParam != 0;
                        if (h && h->hwnd && IsWindow(h->hwnd)) {
                            SetWindowPos(h->hwnd, onTop ? HWND_TOPMOST : HWND_NOTOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE);
                        }
                        continue;
                    } else if (msg.message == WM_NV_DESTROY) {
                        auto* h = reinterpret_cast<WinHolder*>(msg.wParam);
                        if (h && h->hwnd && IsWindow(h->hwnd)) {
                            if (!h->closed) {
                                h->closed = true;
                                KillTimer(h->hwnd, IDLE_TIMER_ID);
                                if (h->cb.onClose) h->cb.onClose(h->cb.userData);
                            }
                            DestroyWindow(h->hwnd);
                        }
                        continue;
                    }
                }
                TranslateMessage(&msg);
                DispatchMessageW(&msg);
            }
        }).detach();
        std::unique_lock<std::mutex> lk(m);
        cv.wait(lk, [&]() { return ready; });
    });
}

} // namespace

void* nvGuiCreateWindow(const char* title, int width, int height, bool alwaysOnTop, const EditorCallbacks& cb, void** outNativeView) {
    ensureGuiThread();
    CreateReq req;
    req.title = title ? title : "VST Plugin";
    req.w = width > 80 ? width : 640;
    req.h = height > 80 ? height : 420;
    req.onTop = alwaysOnTop;
    req.cb = cb;
    PostThreadMessageW(gGuiThreadId.load(), WM_NV_CREATE, 0, reinterpret_cast<LPARAM>(&req));
    std::unique_lock<std::mutex> lk(req.mtx);
    req.cv.wait(lk, [&]() { return req.done; });
    if (outNativeView) *outNativeView = req.result ? static_cast<void*>(req.result->hwnd) : nullptr;
    return req.result;
}

void nvGuiResizeWindow(void* winHandle, int width, int height) {
    if (!winHandle || !gGuiThreadId.load()) return;
    PostThreadMessageW(gGuiThreadId.load(), WM_NV_RESIZE, reinterpret_cast<WPARAM>(winHandle), MAKELPARAM(width, height));
}

void nvGuiSetOnTop(void* winHandle, bool alwaysOnTop) {
    if (!winHandle || !gGuiThreadId.load()) return;
    PostThreadMessageW(gGuiThreadId.load(), WM_NV_ONTOP, reinterpret_cast<WPARAM>(winHandle), alwaysOnTop ? 1 : 0);
}

void nvGuiDestroyWindow(void* winHandle) {
    if (!winHandle || !gGuiThreadId.load()) return;
    PostThreadMessageW(gGuiThreadId.load(), WM_NV_DESTROY, reinterpret_cast<WPARAM>(winHandle), 0);
}
#endif
