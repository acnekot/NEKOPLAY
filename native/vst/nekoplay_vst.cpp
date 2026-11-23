#define MINIAUDIO_IMPLEMENTATION
#define MA_NO_DECODING
#define MA_NO_ENCODING
#define MA_NO_RESOURCE_MANAGER
#define MA_NO_NODE_GRAPH
#define MA_NO_GENERATION
#include "miniaudio.h"

#include "nekoplay_vst.h"
#include "vst_common.h"

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <sstream>
#include <thread>

#ifdef _WIN32
#include <windows.h>
#else
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

namespace {

std::mutex gErrMutex;
std::string gLastError;

std::mutex gChainMutex;
std::vector<std::unique_ptr<VstPluginInstance>> gChain;
std::atomic<int> gNextSlotId{1};

RingBuffer gRing(131072); // ~1.3s stereo float32 buffer capacity
std::atomic<bool> gEngineRunning{false};
int gSampleRate = 48000;
int gChannels = 2;
std::string gPipePath;
ma_device gDevice{};
bool gDeviceInit = false;
std::thread gPipeThread;

// Pre-allocated scratch buffers for audio callback (zero allocations on audio thread)
constexpr int MAX_BLOCK = 4096;
float gInInterleaved[MAX_BLOCK * 2];
float gLeft[MAX_BLOCK];
float gRight[MAX_BLOCK];

void audioCallback(ma_device* /*dev*/, void* pOutput, const void* /*pInput*/, ma_uint32 frameCount) {
    auto* out = static_cast<float*>(pOutput);
    ma_uint32 total = frameCount;
    ma_uint32 offset = 0;
    while (offset < total) {
        int chunk = static_cast<int>(std::min<ma_uint32>(total - offset, MAX_BLOCK));
        size_t want = static_cast<size_t>(chunk) * 2;
        size_t got = gRing.read(gInInterleaved, want);
        if (got < want) {
            std::memset(gInInterleaved + got, 0, (want - got) * sizeof(float));
        }
        for (int i = 0; i < chunk; ++i) {
            gLeft[i] = gInInterleaved[i * 2];
            gRight[i] = gInInterleaved[i * 2 + 1];
        }
        {
            std::unique_lock<std::mutex> lk(gChainMutex, std::try_to_lock);
            if (lk.owns_lock()) {
                for (auto& p : gChain) {
                    if (p && !p->bypassed.load(std::memory_order_relaxed)) {
                        p->process(gLeft, gRight, chunk);
                    }
                }
            }
        }
        float* dst = out + offset * 2;
        for (int i = 0; i < chunk; ++i) {
            dst[i * 2] = gLeft[i];
            dst[i * 2 + 1] = gRight[i];
        }
        offset += static_cast<ma_uint32>(chunk);
    }
}

void pipeReaderLoop() {
    float buf[2048];
#ifdef _WIN32
    int wlen = MultiByteToWideChar(CP_UTF8, 0, gPipePath.c_str(), -1, nullptr, 0);
    std::wstring wpipe(static_cast<size_t>(wlen), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, gPipePath.c_str(), -1, wpipe.data(), wlen);
    while (gEngineRunning.load()) {
        HANDLE hPipe = CreateNamedPipeW(
            wpipe.c_str(),
            PIPE_ACCESS_INBOUND,
            PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT,
            1, 0, 65536, 100, nullptr);
        if (hPipe == INVALID_HANDLE_VALUE) {
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
            continue;
        }
        BOOL connected = ConnectNamedPipe(hPipe, nullptr) ? TRUE : (GetLastError() == ERROR_PIPE_CONNECTED);
        if (connected) {
            while (gEngineRunning.load()) {
                DWORD bytesRead = 0;
                if (!ReadFile(hPipe, buf, sizeof(buf), &bytesRead, nullptr) || bytesRead == 0) break;
                size_t floats = bytesRead / sizeof(float);
                size_t written = 0;
                while (written < floats && gEngineRunning.load()) {
                    size_t w = gRing.write(buf + written, floats - written);
                    written += w;
                    if (w == 0) std::this_thread::sleep_for(std::chrono::milliseconds(2));
                }
            }
        }
        DisconnectNamedPipe(hPipe);
        CloseHandle(hPipe);
    }
#else
    while (gEngineRunning.load()) {
        int fd = open(gPipePath.c_str(), O_RDONLY | O_NONBLOCK);
        if (fd < 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
            continue;
        }
        while (gEngineRunning.load()) {
            ssize_t n = read(fd, buf, sizeof(buf));
            if (n > 0) {
                size_t floats = static_cast<size_t>(n) / sizeof(float);
                size_t written = 0;
                while (written < floats && gEngineRunning.load()) {
                    size_t w = gRing.write(buf + written, floats - written);
                    written += w;
                    if (w == 0) std::this_thread::sleep_for(std::chrono::milliseconds(2));
                }
            } else if (n == 0) {
                // Writer closed (track switch / pause): sleep briefly and wait for next write
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
            } else {
                if (errno == EAGAIN || errno == EWOULDBLOCK) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(3));
                } else {
                    break;
                }
            }
        }
        close(fd);
    }
#endif
}

std::vector<std::string> parseJsonStringArray(const char* json) {
    std::vector<std::string> out;
    if (!json) return out;
    std::string s(json);
    size_t i = 0;
    while (i < s.size()) {
        if (s[i] == '"') {
            ++i;
            std::string cur;
            while (i < s.size() && s[i] != '"') {
                if (s[i] == '\\' && i + 1 < s.size()) {
                    ++i;
                    cur.push_back(s[i]);
                } else {
                    cur.push_back(s[i]);
                }
                ++i;
            }
            if (!cur.empty()) out.push_back(cur);
        }
        ++i;
    }
    return out;
}

std::vector<std::string> defaultVstDirs() {
    std::vector<std::string> dirs;
#ifdef _WIN32
    dirs.push_back("C:\\Program Files\\Common Files\\VST3");
    dirs.push_back("C:\\Program Files\\VstPlugins");
    dirs.push_back("C:\\Program Files\\Steinberg\\VstPlugins");
    dirs.push_back("C:\\Program Files\\Common Files\\VST2");
    if (const char* localApp = std::getenv("LOCALAPPDATA")) {
        dirs.push_back(std::string(localApp) + "\\Programs\\Common\\VST3");
    }
#elif defined(__APPLE__)
    dirs.push_back("/Library/Audio/Plug-Ins/VST3");
    dirs.push_back("/Library/Audio/Plug-Ins/VST");
    if (const char* home = std::getenv("HOME")) {
        dirs.push_back(std::string(home) + "/Library/Audio/Plug-Ins/VST3");
        dirs.push_back(std::string(home) + "/Library/Audio/Plug-Ins/VST");
    }
#else
    dirs.push_back("/usr/lib/vst3");
    dirs.push_back("/usr/local/lib/vst3");
    dirs.push_back("/usr/lib/vst");
    if (const char* home = std::getenv("HOME")) {
        dirs.push_back(std::string(home) + "/.vst3");
        dirs.push_back(std::string(home) + "/.vst");
    }
#endif
    return dirs;
}

VstPluginInstance* findSlotLocked(int id) {
    for (auto& p : gChain) {
        if (p && p->slotId == id) return p.get();
    }
    return nullptr;
}

} // namespace

void nvSetError(const std::string& err) {
    std::lock_guard<std::mutex> lk(gErrMutex);
    gLastError = err;
}

std::string nvGetError() {
    std::lock_guard<std::mutex> lk(gErrMutex);
    return gLastError;
}

std::string jsonEscape(const std::string& s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (unsigned char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out.push_back(static_cast<char>(c));
                }
        }
    }
    return out;
}

char* dupStr(const std::string& s) {
    char* p = static_cast<char*>(std::malloc(s.size() + 1));
    if (p) std::memcpy(p, s.c_str(), s.size() + 1);
    return p;
}

NV_API int nv_init() {
    nvSetError("");
    return 1;
}

NV_API void nv_shutdown() {
    nv_stop_engine();
    std::lock_guard<std::mutex> lk(gChainMutex);
    gChain.clear();
}

NV_API const char* nv_start_engine(int sampleRate, int channels) {
    if (gEngineRunning.load()) return dupStr(gPipePath);
    gSampleRate = sampleRate > 0 ? sampleRate : 48000;
    gChannels = channels == 2 ? 2 : 2;
    gRing.clear();

#ifdef _WIN32
    gPipePath = "\\\\.\\pipe\\nekoplay-vst-" + std::to_string(GetCurrentProcessId());
#else
    gPipePath = "/tmp/nekoplay-vst-" + std::to_string(getpid()) + ".pcm";
    unlink(gPipePath.c_str());
    if (mkfifo(gPipePath.c_str(), 0600) != 0) {
        nvSetError("创建 PCM 音频管道失败：" + gPipePath);
        return nullptr;
    }
#endif

    ma_device_config cfg = ma_device_config_init(ma_device_type_playback);
    cfg.playback.format = ma_format_f32;
    cfg.playback.channels = static_cast<ma_uint32>(gChannels);
    cfg.sampleRate = static_cast<ma_uint32>(gSampleRate);
    cfg.dataCallback = audioCallback;
    cfg.periodSizeInFrames = 512;

    if (ma_device_init(nullptr, &cfg, &gDevice) != MA_SUCCESS) {
        nvSetError("初始化音频输出设备失败（miniaudio）");
        return nullptr;
    }
    gDeviceInit = true;
    if (ma_device_start(&gDevice) != MA_SUCCESS) {
        ma_device_uninit(&gDevice);
        gDeviceInit = false;
        nvSetError("启动音频输出设备失败（miniaudio）");
        return nullptr;
    }

    {
        std::lock_guard<std::mutex> lk(gChainMutex);
        for (auto& p : gChain) {
            if (p) p->prepare(static_cast<double>(gSampleRate), 512);
        }
    }

    gEngineRunning.store(true);
    gPipeThread = std::thread(pipeReaderLoop);
    return dupStr(gPipePath);
}

NV_API void nv_stop_engine() {
    if (!gEngineRunning.exchange(false)) return;
#ifdef _WIN32
    // Wake up blocked ConnectNamedPipe
    if (!gPipePath.empty()) {
        int wlen = MultiByteToWideChar(CP_UTF8, 0, gPipePath.c_str(), -1, nullptr, 0);
        std::wstring wpipe(static_cast<size_t>(wlen), L'\0');
        MultiByteToWideChar(CP_UTF8, 0, gPipePath.c_str(), -1, wpipe.data(), wlen);
        HANDLE dummy = CreateFileW(wpipe.c_str(), GENERIC_WRITE, 0, nullptr, OPEN_EXISTING, 0, nullptr);
        if (dummy != INVALID_HANDLE_VALUE) CloseHandle(dummy);
    }
#endif
    if (gPipeThread.joinable()) gPipeThread.join();
    if (gDeviceInit) {
        ma_device_uninit(&gDevice);
        gDeviceInit = false;
    }
#ifndef _WIN32
    if (!gPipePath.empty()) unlink(gPipePath.c_str());
#endif
    gRing.clear();
}

NV_API int nv_engine_running() {
    return gEngineRunning.load() ? 1 : 0;
}

NV_API const char* nv_scan_dirs(const char* dirsJson) {
    namespace fs = std::filesystem;
    std::vector<std::string> dirs = defaultVstDirs();
    for (auto& extra : parseJsonStringArray(dirsJson)) dirs.push_back(extra);

    struct Found { std::string path, name, format; };
    std::vector<Found> items;
    std::vector<std::string> seen;

    auto addFound = [&](const fs::path& p, const std::string& fmt) {
        std::string sp = p.string();
        if (std::find(seen.begin(), seen.end(), sp) != seen.end()) return;
        seen.push_back(sp);
        items.push_back({sp, p.stem().string(), fmt});
    };

    for (const auto& d : dirs) {
        std::error_code ec;
        fs::path root(d);
        if (!fs::exists(root, ec) || !fs::is_directory(root, ec)) continue;
        for (auto it = fs::recursive_directory_iterator(root, fs::directory_options::skip_permission_denied, ec);
             !ec && it != fs::recursive_directory_iterator(); it.increment(ec)) {
            if (it.depth() > 3) { it.disable_recursion_pending(); continue; }
            auto p = it->path();
            std::string ext = p.extension().string();
            std::transform(ext.begin(), ext.end(), ext.begin(), ::tolower);
            if (ext == ".vst3") {
                addFound(p, "VST3");
                it.disable_recursion_pending();
            } else if (ext == ".vst") {
                addFound(p, "VST2");
                it.disable_recursion_pending();
            }
#ifdef _WIN32
            else if (ext == ".dll" && it->is_regular_file(ec)) {
                addFound(p, "VST2");
            }
#elif !defined(__APPLE__)
            else if (ext == ".so" && it->is_regular_file(ec)) {
                addFound(p, "VST2");
            }
#endif
        }
    }

    std::ostringstream oss;
    oss << "[";
    for (size_t i = 0; i < items.size(); ++i) {
        if (i) oss << ",";
        oss << "{\"path\":\"" << jsonEscape(items[i].path)
            << "\",\"name\":\"" << jsonEscape(items[i].name)
            << "\",\"format\":\"" << items[i].format << "\"}";
    }
    oss << "]";
    return dupStr(oss.str());
}

NV_API int nv_add_plugin(const char* path) {
    if (!path || !path[0]) { nvSetError("插件路径为空"); return -1; }
    std::string sp(path);
    std::string ext = std::filesystem::path(sp).extension().string();
    std::transform(ext.begin(), ext.end(), ext.begin(), ::tolower);

    std::unique_ptr<VstPluginInstance> inst;
    if (ext == ".vst3") inst = loadVst3Plugin(sp);
    else inst = loadVst2Plugin(sp);
    if (!inst) return -1;

    inst->slotId = gNextSlotId.fetch_add(1);
    inst->prepare(static_cast<double>(gSampleRate), 512);
    int id = inst->slotId;
    {
        std::lock_guard<std::mutex> lk(gChainMutex);
        gChain.push_back(std::move(inst));
    }
    return id;
}

NV_API int nv_remove_plugin(int id) {
    std::unique_ptr<VstPluginInstance> removed;
    {
        std::lock_guard<std::mutex> lk(gChainMutex);
        for (auto it = gChain.begin(); it != gChain.end(); ++it) {
            if (*it && (*it)->slotId == id) {
                removed = std::move(*it);
                gChain.erase(it);
                break;
            }
        }
    }
    return removed ? 1 : 0;
}

NV_API int nv_move_plugin(int id, int newIndex) {
    std::lock_guard<std::mutex> lk(gChainMutex);
    int cur = -1;
    for (size_t i = 0; i < gChain.size(); ++i) {
        if (gChain[i] && gChain[i]->slotId == id) { cur = static_cast<int>(i); break; }
    }
    if (cur < 0) return 0;
    int target = std::clamp(newIndex, 0, static_cast<int>(gChain.size()) - 1);
    if (cur == target) return 1;
    auto item = std::move(gChain[static_cast<size_t>(cur)]);
    gChain.erase(gChain.begin() + cur);
    gChain.insert(gChain.begin() + target, std::move(item));
    return 1;
}

NV_API int nv_set_bypass(int id, int bypass) {
    std::lock_guard<std::mutex> lk(gChainMutex);
    auto* p = findSlotLocked(id);
    if (!p) return 0;
    p->bypassed.store(bypass != 0, std::memory_order_relaxed);
    return 1;
}

NV_API const char* nv_get_chain() {
    std::lock_guard<std::mutex> lk(gChainMutex);
    std::ostringstream oss;
    oss << "[";
    for (size_t i = 0; i < gChain.size(); ++i) {
        auto& p = gChain[i];
        if (!p) continue;
        if (i) oss << ",";
        oss << "{\"id\":" << p->slotId
            << ",\"path\":\"" << jsonEscape(p->path)
            << "\",\"name\":\"" << jsonEscape(p->name)
            << "\",\"format\":\"" << p->format
            << "\",\"bypass\":" << (p->bypassed.load() ? "true" : "false")
            << ",\"editorOpen\":" << (p->guiHandle ? "true" : "false") << "}";
    }
    oss << "]";
    return dupStr(oss.str());
}

NV_API const char* nv_get_params(int id) {
    std::vector<VstParamInfo> params;
    {
        std::lock_guard<std::mutex> lk(gChainMutex);
        auto* p = findSlotLocked(id);
        if (p) params = p->getParams();
    }
    std::ostringstream oss;
    oss << "[";
    for (size_t i = 0; i < params.size(); ++i) {
        if (i) oss << ",";
        oss << "{\"index\":" << params[i].index
            << ",\"id\":" << params[i].id
            << ",\"name\":\"" << jsonEscape(params[i].name)
            << "\",\"value\":" << params[i].value
            << ",\"display\":\"" << jsonEscape(params[i].display) << "\"}";
    }
    oss << "]";
    return dupStr(oss.str());
}

NV_API int nv_set_param(int id, int index, float value) {
    std::lock_guard<std::mutex> lk(gChainMutex);
    auto* p = findSlotLocked(id);
    return (p && p->setParam(index, value)) ? 1 : 0;
}

NV_API int nv_open_editor(int id, int alwaysOnTop) {
    std::lock_guard<std::mutex> lk(gChainMutex);
    auto* p = findSlotLocked(id);
    if (!p) { nvSetError("未找到该 VST 插件槽位"); return 0; }
    return p->openEditor(alwaysOnTop != 0) ? 1 : 0;
}

NV_API int nv_set_editor_ontop(int id, int alwaysOnTop) {
    std::lock_guard<std::mutex> lk(gChainMutex);
    auto* p = findSlotLocked(id);
    if (!p) return 0;
    p->setEditorOnTop(alwaysOnTop != 0);
    return 1;
}

NV_API int nv_close_editor(int id) {
    std::lock_guard<std::mutex> lk(gChainMutex);
    auto* p = findSlotLocked(id);
    if (!p) return 0;
    p->closeEditor();
    return 1;
}

NV_API const char* nv_last_error() {
    return dupStr(nvGetError());
}

NV_API void nv_free_str(const char* s) {
    if (s) std::free(const_cast<char*>(s));
}
