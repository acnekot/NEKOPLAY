#include "vst_common.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <filesystem>

#ifdef _WIN32
#include <windows.h>
#define VST_CALL __cdecl
#elif defined(__APPLE__)
#include <CoreFoundation/CoreFoundation.h>
#include <dlfcn.h>
#define VST_CALL
#else
#include <dlfcn.h>
#define VST_CALL
#endif

namespace {

// Clean-room VST2 C ABI definitions
struct AEffect;
using AudioMasterCallback = intptr_t(VST_CALL*)(AEffect* effect, int32_t opcode, int32_t index, intptr_t value, void* ptr, float opt);
using AEffectDispatcher = intptr_t(VST_CALL*)(AEffect* effect, int32_t opcode, int32_t index, intptr_t value, void* ptr, float opt);
using AEffectProcess = void(VST_CALL*)(AEffect* effect, float** inputs, float** outputs, int32_t sampleFrames);
using AEffectSetParam = void(VST_CALL*)(AEffect* effect, int32_t index, float parameter);
using AEffectGetParam = float(VST_CALL*)(AEffect* effect, int32_t index);
using VstMainProc = AEffect*(VST_CALL*)(AudioMasterCallback audioMaster);

struct ERect {
    int16_t top;
    int16_t left;
    int16_t bottom;
    int16_t right;
};

struct AEffect {
    int32_t magic;
    AEffectDispatcher dispatcher;
    AEffectProcess process;
    AEffectSetParam setParameter;
    AEffectGetParam getParameter;
    int32_t numPrograms;
    int32_t numParams;
    int32_t numInputs;
    int32_t numOutputs;
    int32_t flags;
    intptr_t resvd1;
    intptr_t resvd2;
    int32_t initialDelay;
    int32_t realQualities;
    int32_t offQualities;
    float ioRatio;
    void* object;
    void* user;
    int32_t uniqueID;
    int32_t version;
    AEffectProcess processReplacing;
    void* processDoubleReplacing;
    char future[56];
};

constexpr int32_t kEffectMagic = 0x56737450; // 'VstP'
constexpr int32_t effOpen = 0;
constexpr int32_t effClose = 1;
constexpr int32_t effGetParamDisplay = 7;
constexpr int32_t effGetParamName = 8;
constexpr int32_t effSetSampleRate = 10;
constexpr int32_t effSetBlockSize = 11;
constexpr int32_t effMainsChanged = 12;
constexpr int32_t effEditGetRect = 13;
constexpr int32_t effEditOpen = 14;
constexpr int32_t effEditClose = 15;
constexpr int32_t effEditIdle = 19;
constexpr int32_t effGetEffectName = 45;
constexpr int32_t effFlagsHasEditor = 1 << 0;

constexpr int32_t audioMasterVersion = 1;
constexpr int32_t audioMasterCurrentId = 2;
constexpr int32_t audioMasterIdle = 3;
constexpr int32_t audioMasterGetSampleRate = 16;
constexpr int32_t audioMasterGetBlockSize = 17;
constexpr int32_t audioMasterGetVendorString = 32;
constexpr int32_t audioMasterGetProductString = 33;
constexpr int32_t audioMasterGetVendorVersion = 34;
constexpr int32_t audioMasterCanDo = 37;
constexpr int32_t audioMasterGetLanguage = 38;
constexpr int32_t audioMasterSizeWindow = 15;

class Vst2Instance final : public VstPluginInstance {
public:
    AEffect* fx = nullptr;
    double sampleRate_ = 48000.0;
    int blockSize_ = 512;
    std::vector<float> outL_, outR_, dummyBuf_;
    std::vector<float*> inPtrs_, outPtrs_;
    std::mutex procMutex_;
    bool editorOpen_ = false;
#ifdef _WIN32
    HMODULE mod = nullptr;
#elif defined(__APPLE__)
    CFBundleRef bundle = nullptr;
    void* dl = nullptr;
#else
    void* dl = nullptr;
#endif

    static intptr_t VST_CALL hostCallback(AEffect* e, int32_t opcode, int32_t index, intptr_t value, void* ptr, float /*opt*/) {
        auto* self = (e && e->user) ? reinterpret_cast<Vst2Instance*>(e->user) : nullptr;
        switch (opcode) {
            case audioMasterVersion: return 2400;
            case audioMasterCurrentId: return e ? e->uniqueID : 0;
            case audioMasterIdle: return 0;
            case audioMasterGetSampleRate: return self ? static_cast<intptr_t>(self->sampleRate_) : 48000;
            case audioMasterGetBlockSize: return self ? self->blockSize_ : 512;
            case audioMasterGetVendorString:
                if (ptr) std::strncpy(static_cast<char*>(ptr), "NEKOPLAY", 63);
                return 1;
            case audioMasterGetProductString:
                if (ptr) std::strncpy(static_cast<char*>(ptr), "NEKOPLAY", 63);
                return 1;
            case audioMasterGetVendorVersion: return 1000;
            case audioMasterGetLanguage: return 1; // English
            case audioMasterSizeWindow:
                if (self && self->guiHandle) {
                    nvGuiResizeWindow(self->guiHandle, index, static_cast<int>(value));
                    return 1;
                }
                return 0;
            case audioMasterCanDo:
                if (ptr && (std::strcmp(static_cast<const char*>(ptr), "sizeWindow") == 0 ||
                            std::strcmp(static_cast<const char*>(ptr), "supplyIdle") == 0)) {
                    return 1;
                }
                return 0;
            default:
                return 0;
        }
    }

    ~Vst2Instance() override {
        closeEditor();
        if (fx) {
            fx->dispatcher(fx, effMainsChanged, 0, 0, nullptr, 0.0f);
            fx->dispatcher(fx, effClose, 0, 0, nullptr, 0.0f);
            fx = nullptr;
        }
#ifdef _WIN32
        if (mod) FreeLibrary(mod);
#elif defined(__APPLE__)
        if (dl) dlclose(dl);
        if (bundle) CFRelease(bundle);
#else
        if (dl) dlclose(dl);
#endif
    }

    bool prepare(double sampleRate, int maxBlockSize) override {
        std::lock_guard<std::mutex> lk(procMutex_);
        sampleRate_ = sampleRate;
        blockSize_ = maxBlockSize;
        outL_.assign(static_cast<size_t>(maxBlockSize), 0.0f);
        outR_.assign(static_cast<size_t>(maxBlockSize), 0.0f);
        dummyBuf_.assign(static_cast<size_t>(maxBlockSize), 0.0f);
        if (!fx) return false;
        inPtrs_.resize(static_cast<size_t>(std::max(2, fx->numInputs)));
        outPtrs_.resize(static_cast<size_t>(std::max(2, fx->numOutputs)));
        fx->dispatcher(fx, effMainsChanged, 0, 0, nullptr, 0.0f);
        fx->dispatcher(fx, effSetSampleRate, 0, 0, nullptr, static_cast<float>(sampleRate));
        fx->dispatcher(fx, effSetBlockSize, 0, maxBlockSize, nullptr, 0.0f);
        fx->dispatcher(fx, effMainsChanged, 0, 1, nullptr, 0.0f);
        return true;
    }

    void process(float* left, float* right, int numFrames) override {
        if (!fx || bypassed.load(std::memory_order_relaxed) || numFrames <= 0) return;
        std::unique_lock<std::mutex> lk(procMutex_, std::try_to_lock);
        if (!lk.owns_lock()) return;
        if (static_cast<int>(outL_.size()) < numFrames) {
            outL_.resize(static_cast<size_t>(numFrames));
            outR_.resize(static_cast<size_t>(numFrames));
            dummyBuf_.assign(static_cast<size_t>(numFrames), 0.0f);
        }
        std::memcpy(outL_.data(), left, static_cast<size_t>(numFrames) * sizeof(float));
        std::memcpy(outR_.data(), right, static_cast<size_t>(numFrames) * sizeof(float));
        if (inPtrs_.size() < 2) inPtrs_.resize(static_cast<size_t>(std::max(2, fx->numInputs)));
        if (outPtrs_.size() < 2) outPtrs_.resize(static_cast<size_t>(std::max(2, fx->numOutputs)));
        inPtrs_[0] = left;
        inPtrs_[1] = right;
        for (size_t i = 2; i < inPtrs_.size(); ++i) inPtrs_[i] = dummyBuf_.data();
        outPtrs_[0] = outL_.data();
        outPtrs_[1] = outR_.data();
        for (size_t i = 2; i < outPtrs_.size(); ++i) outPtrs_[i] = dummyBuf_.data();
        if (fx->processReplacing) {
            fx->processReplacing(fx, inPtrs_.data(), outPtrs_.data(), numFrames);
            if (fx->numOutputs > 0) {
                std::memcpy(left, outL_.data(), static_cast<size_t>(numFrames) * sizeof(float));
                if (fx->numOutputs > 1) {
                    std::memcpy(right, outR_.data(), static_cast<size_t>(numFrames) * sizeof(float));
                } else {
                    std::memcpy(right, outL_.data(), static_cast<size_t>(numFrames) * sizeof(float));
                }
            }
        }
    }

    std::vector<VstParamInfo> getParams() override {
        std::vector<VstParamInfo> res;
        if (!fx) return res;
        int n = std::min(fx->numParams, 256);
        for (int i = 0; i < n; ++i) {
            char pname[64] = {0};
            char pdisp[64] = {0};
            fx->dispatcher(fx, effGetParamName, i, 0, pname, 0.0f);
            fx->dispatcher(fx, effGetParamDisplay, i, 0, pdisp, 0.0f);
            float val = fx->getParameter ? fx->getParameter(fx, i) : 0.0f;
            VstParamInfo p;
            p.index = i;
            p.id = static_cast<uint32_t>(i);
            p.name = pname[0] ? std::string(pname) : ("Param " + std::to_string(i));
            p.value = val;
            p.display = std::string(pdisp);
            res.push_back(std::move(p));
        }
        return res;
    }

    bool setParam(int index, float value) override {
        if (!fx || !fx->setParameter || index < 0 || index >= fx->numParams) return false;
        fx->setParameter(fx, index, std::clamp(value, 0.0f, 1.0f));
        return true;
    }

    static bool onEditorAttach(void* ud, void* nativeView) {
        auto* self = static_cast<Vst2Instance*>(ud);
        if (!self || !self->fx || !nativeView) return false;
        self->fx->dispatcher(self->fx, effEditOpen, 0, 0, nativeView, 0.0f);
        self->editorOpen_ = true;
        return true;
    }

    static void onEditorIdle(void* ud) {
        auto* self = static_cast<Vst2Instance*>(ud);
        if (self && self->fx && self->editorOpen_) {
            self->fx->dispatcher(self->fx, effEditIdle, 0, 0, nullptr, 0.0f);
        }
    }

    static void onEditorClose(void* ud) {
        auto* self = static_cast<Vst2Instance*>(ud);
        if (!self) return;
        if (self->fx && self->editorOpen_) {
            self->editorOpen_ = false;
            self->fx->dispatcher(self->fx, effEditClose, 0, 0, nullptr, 0.0f);
        }
        self->guiHandle = nullptr;
    }

    bool openEditor(bool alwaysOnTop) override {
        if (!fx) return false;
        if (!(fx->flags & effFlagsHasEditor)) {
            nvSetError("该 VST2 插件不提供原生界面");
            return false;
        }
        if (guiHandle) {
            nvGuiSetOnTop(guiHandle, alwaysOnTop);
            return true;
        }
        ERect* rect = nullptr;
        fx->dispatcher(fx, effEditGetRect, 0, 0, &rect, 0.0f);
        int w = (rect && rect->right > rect->left) ? (rect->right - rect->left) : 640;
        int h = (rect && rect->bottom > rect->top) ? (rect->bottom - rect->top) : 420;
        EditorCallbacks cb;
        cb.userData = this;
        cb.onAttach = &Vst2Instance::onEditorAttach;
        cb.onIdle = &Vst2Instance::onEditorIdle;
        cb.onClose = &Vst2Instance::onEditorClose;
        void* nativeView = nullptr;
        guiHandle = nvGuiCreateWindow(name.c_str(), w, h, alwaysOnTop, cb, &nativeView);
        if (!guiHandle || !editorOpen_) {
            guiHandle = nullptr;
            nvSetError("创建 VST2 插件窗口失败");
            return false;
        }
        rect = nullptr;
        fx->dispatcher(fx, effEditGetRect, 0, 0, &rect, 0.0f);
        if (rect && rect->right > rect->left && rect->bottom > rect->top) {
            nvGuiResizeWindow(guiHandle, rect->right - rect->left, rect->bottom - rect->top);
        }
        return true;
    }

    void setEditorOnTop(bool alwaysOnTop) override {
        if (guiHandle) nvGuiSetOnTop(guiHandle, alwaysOnTop);
    }

    void closeEditor() override {
        if (guiHandle) {
            void* h = guiHandle;
            guiHandle = nullptr;
            nvGuiDestroyWindow(h);
        }
    }
};

} // namespace

std::unique_ptr<VstPluginInstance> loadVst2Plugin(const std::string& path) {
    auto inst = std::make_unique<Vst2Instance>();
    inst->path = path;
    inst->format = "VST2";
    inst->name = std::filesystem::path(path).stem().string();

    VstMainProc mainProc = nullptr;
#ifdef _WIN32
    int wlen = MultiByteToWideChar(CP_UTF8, 0, path.c_str(), -1, nullptr, 0);
    std::wstring wpath(static_cast<size_t>(wlen), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, path.c_str(), -1, wpath.data(), wlen);
    inst->mod = LoadLibraryW(wpath.c_str());
    if (!inst->mod) {
        nvSetError("无法加载 VST2 DLL：" + path);
        return nullptr;
    }
    mainProc = reinterpret_cast<VstMainProc>(GetProcAddress(inst->mod, "VSTPluginMain"));
    if (!mainProc) mainProc = reinterpret_cast<VstMainProc>(GetProcAddress(inst->mod, "main"));
#elif defined(__APPLE__)
    CFStringRef cfPath = CFStringCreateWithCString(kCFAllocatorDefault, path.c_str(), kCFStringEncodingUTF8);
    CFURLRef url = CFURLCreateWithFileSystemPath(kCFAllocatorDefault, cfPath, kCFURLPOSIXPathStyle, true);
    CFRelease(cfPath);
    if (url) {
        inst->bundle = CFBundleCreate(kCFAllocatorDefault, url);
        CFRelease(url);
    }
    if (inst->bundle) {
        mainProc = reinterpret_cast<VstMainProc>(CFBundleGetFunctionPointerForName(inst->bundle, CFSTR("VSTPluginMain")));
        if (!mainProc) mainProc = reinterpret_cast<VstMainProc>(CFBundleGetFunctionPointerForName(inst->bundle, CFSTR("main_macho")));
    }
    if (!mainProc) {
        inst->dl = dlopen(path.c_str(), RTLD_LAZY);
        if (inst->dl) {
            mainProc = reinterpret_cast<VstMainProc>(dlsym(inst->dl, "VSTPluginMain"));
            if (!mainProc) mainProc = reinterpret_cast<VstMainProc>(dlsym(inst->dl, "main_macho"));
        }
    }
#else
    inst->dl = dlopen(path.c_str(), RTLD_LAZY);
    if (inst->dl) {
        mainProc = reinterpret_cast<VstMainProc>(dlsym(inst->dl, "VSTPluginMain"));
        if (!mainProc) mainProc = reinterpret_cast<VstMainProc>(dlsym(inst->dl, "main"));
    }
#endif

    if (!mainProc) {
        nvSetError("不是有效的 VST2 插件（未找到 VSTPluginMain）：" + path);
        return nullptr;
    }

    AEffect* fx = mainProc(&Vst2Instance::hostCallback);
    if (!fx || fx->magic != kEffectMagic) {
        nvSetError("VST2 插件初始化失败：" + path);
        return nullptr;
    }

    fx->user = inst.get();
    inst->fx = fx;
    fx->dispatcher(fx, effOpen, 0, 0, nullptr, 0.0f);
    char ename[64] = {0};
    fx->dispatcher(fx, effGetEffectName, 0, 0, ename, 0.0f);
    if (ename[0]) inst->name = ename;
    return inst;
}
