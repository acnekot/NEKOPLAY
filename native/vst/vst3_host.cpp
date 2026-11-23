#include "vst_common.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <filesystem>

#ifdef _WIN32
#include <windows.h>
#include <objbase.h>
#define SMTG_CALL __stdcall
#elif defined(__APPLE__)
#include <CoreFoundation/CoreFoundation.h>
#include <dlfcn.h>
#define SMTG_CALL
#else
#include <dlfcn.h>
#define SMTG_CALL
#endif

namespace {

// Clean-room minimal VST3 COM/FUnknown C++ ABI definitions
using tresult = int32_t;
using TUID = char[16];
using char16 = char16_t;
using ParamID = uint32_t;
using ParamValue = double;

constexpr tresult kResultOk = 0;
constexpr tresult kResultFalse = 1;
constexpr tresult kNotImplemented = -1;
constexpr tresult kNoInterface = -2;

#ifdef _WIN32
#define DEF_UID(name, l1, l2, l3, l4) static constexpr char name[16] = { \
    static_cast<char>((l1 & 0x000000FF)), static_cast<char>((l1 & 0x0000FF00) >> 8), \
    static_cast<char>((l1 & 0x00FF0000) >> 16), static_cast<char>((l1 & 0xFF000000) >> 24), \
    static_cast<char>((l2 & 0x00FF0000) >> 16), static_cast<char>((l2 & 0xFF000000) >> 24), \
    static_cast<char>((l2 & 0x000000FF)), static_cast<char>((l2 & 0x0000FF00) >> 8), \
    static_cast<char>((l3 & 0xFF000000) >> 24), static_cast<char>((l3 & 0x00FF0000) >> 16), \
    static_cast<char>((l3 & 0x0000FF00) >> 8), static_cast<char>((l3 & 0x000000FF)), \
    static_cast<char>((l4 & 0xFF000000) >> 24), static_cast<char>((l4 & 0x00FF0000) >> 16), \
    static_cast<char>((l4 & 0x0000FF00) >> 8), static_cast<char>((l4 & 0x000000FF)) }
#else
#define DEF_UID(name, l1, l2, l3, l4) static constexpr char name[16] = { \
    static_cast<char>((l1 & 0xFF000000) >> 24), static_cast<char>((l1 & 0x00FF0000) >> 16), \
    static_cast<char>((l1 & 0x0000FF00) >> 8), static_cast<char>((l1 & 0x000000FF)), \
    static_cast<char>((l2 & 0xFF000000) >> 24), static_cast<char>((l2 & 0x00FF0000) >> 16), \
    static_cast<char>((l2 & 0x0000FF00) >> 8), static_cast<char>((l2 & 0x000000FF)), \
    static_cast<char>((l3 & 0xFF000000) >> 24), static_cast<char>((l3 & 0x00FF0000) >> 16), \
    static_cast<char>((l3 & 0x0000FF00) >> 8), static_cast<char>((l3 & 0x000000FF)), \
    static_cast<char>((l4 & 0xFF000000) >> 24), static_cast<char>((l4 & 0x00FF0000) >> 16), \
    static_cast<char>((l4 & 0x0000FF00) >> 8), static_cast<char>((l4 & 0x000000FF)) }
#endif

DEF_UID(kFUnknownIID,          0x00000000, 0x00000000, 0xC0000000, 0x00000046);
DEF_UID(kIPluginFactoryIID,    0x7A4D811C, 0x52114A1F, 0xAED9D2EE, 0x0B43BF9F);
DEF_UID(kIPluginBaseIID,       0x22888DDB, 0x156E45AE, 0x8358B348, 0x08190625);
DEF_UID(kIComponentIID,        0xE831FF31, 0xF2D54301, 0x928EBBEE, 0x25697802);
DEF_UID(kIAudioProcessorIID,   0x42043F99, 0xB7DA453C, 0xA569E79D, 0x9AAEC33D);
DEF_UID(kIEditControllerIID,   0xDCD7BBE3, 0x7742448D, 0xA874AACC, 0x979C759E);
DEF_UID(kIConnectionPointIID,  0x70A4156F, 0x6E6E4026, 0x989148BF, 0xAA60D8D1);
DEF_UID(kIPlugViewIID,         0x5BC32507, 0xD06049EA, 0xA6151B52, 0x2B755B29);
DEF_UID(kIPlugFrameIID,        0x367FAF01, 0xAFA94693, 0x8D4DA2A0, 0xED0882A3);
DEF_UID(kIHostApplicationIID,  0x58E595CC, 0xDB2D4969, 0x8B6AAF8C, 0x36A664E5);
DEF_UID(kIComponentHandlerIID, 0x93A0BEA3, 0x0BD045DB, 0x8E890B0C, 0xC1E46AC6);
DEF_UID(kIParameterChangesIID, 0xA4779663, 0x0BB64A56, 0xB44384A8, 0x466FEB9D);

inline bool uidEq(const TUID a, const TUID b) { return std::memcmp(a, b, 16) == 0; }

std::string utf16ToUtf8(const char16* s, int maxLen = 128) {
    std::string out;
    if (!s) return out;
    for (int i = 0; i < maxLen && s[i]; ++i) {
        uint32_t cp = static_cast<uint16_t>(s[i]);
        if (cp < 0x80) out.push_back(static_cast<char>(cp));
        else if (cp < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }
    return out;
}

class FUnknown {
public:
    virtual tresult SMTG_CALL queryInterface(const TUID iid, void** obj) = 0;
    virtual uint32_t SMTG_CALL addRef() = 0;
    virtual uint32_t SMTG_CALL release() = 0;
};

struct PFactoryInfo {
    char vendor[64];
    char url[256];
    char email[128];
    int32_t flags;
};

struct PClassInfo {
    TUID cid;
    int32_t cardinality;
    char category[32];
    char name[64];
};

class IPluginFactory : public FUnknown {
public:
    virtual tresult SMTG_CALL getFactoryInfo(PFactoryInfo* info) = 0;
    virtual int32_t SMTG_CALL countClasses() = 0;
    virtual tresult SMTG_CALL getClassInfo(int32_t index, PClassInfo* info) = 0;
    virtual tresult SMTG_CALL createInstance(const char* cid, const char* iid, void** obj) = 0;
};

class IPluginBase : public FUnknown {
public:
    virtual tresult SMTG_CALL initialize(FUnknown* context) = 0;
    virtual tresult SMTG_CALL terminate() = 0;
};

struct BusInfo {
    int32_t mediaType; // 0 = Audio
    int32_t direction; // 0 = Input, 1 = Output
    int32_t channelCount;
    char16 name[128];
    int32_t busType;
    uint32_t flags;
};

class IComponent : public IPluginBase {
public:
    virtual tresult SMTG_CALL getControllerClassId(TUID classId) = 0;
    virtual tresult SMTG_CALL setIoMode(int32_t mode) = 0;
    virtual int32_t SMTG_CALL getBusCount(int32_t type, int32_t dir) = 0;
    virtual tresult SMTG_CALL getBusInfo(int32_t type, int32_t dir, int32_t index, BusInfo& bus) = 0;
    virtual tresult SMTG_CALL getRoutingInfo(void* inInfo, void* outInfo) = 0;
    virtual tresult SMTG_CALL activateBus(int32_t type, int32_t dir, int32_t index, uint8_t state) = 0;
    virtual tresult SMTG_CALL setActive(uint8_t state) = 0;
    virtual tresult SMTG_CALL setState(void* state) = 0;
    virtual tresult SMTG_CALL getState(void* state) = 0;
};

struct ProcessSetup {
    int32_t processMode;       // 0 = kRealtime
    int32_t symbolicSampleSize;// 0 = kSample32
    int32_t maxSamplesPerBlock;
    double sampleRate;
};

struct AudioBusBuffers {
    int32_t numChannels;
    uint64_t silenceFlags;
    union {
        float** channelBuffers32;
        double** channelBuffers64;
    };
};

struct FrameRate {
    uint32_t framesPerSecond;
    uint32_t flags;
};

struct Chord {
    uint8_t keyNote;
    uint8_t rootNote;
    int16_t chordMask;
};

struct ProcessContext {
    uint32_t state;
    double sampleRate;
    int64_t projectTimeSamples;
    int64_t systemTime;
    int64_t continousTimeSamples;
    double projectTimeMusic;
    double barPositionMusic;
    double cycleStartMusic;
    double cycleEndMusic;
    double tempo;
    int32_t timeSigNumerator;
    int32_t timeSigDenominator;
    Chord chord;
    int32_t smpteOffsetSubframes;
    FrameRate frameRate;
    int32_t samplesToNextClock;
};

class IParameterChanges : public FUnknown {
public:
    virtual int32_t SMTG_CALL getParameterCount() = 0;
    virtual void* SMTG_CALL getParameterData(int32_t index) = 0;
    virtual void* SMTG_CALL addParameterData(const ParamID& id, int32_t& index) = 0;
};

class EmptyParamChanges final : public IParameterChanges {
    std::atomic<uint32_t> refs_{1};
public:
    tresult SMTG_CALL queryInterface(const TUID iid, void** obj) override {
        if (!obj) return kResultFalse;
        if (uidEq(iid, kFUnknownIID) || uidEq(iid, kIParameterChangesIID)) {
            addRef();
            *obj = static_cast<IParameterChanges*>(this);
            return kResultOk;
        }
        *obj = nullptr;
        return kNoInterface;
    }
    uint32_t SMTG_CALL addRef() override { return ++refs_; }
    uint32_t SMTG_CALL release() override { return --refs_; }
    int32_t SMTG_CALL getParameterCount() override { return 0; }
    void* SMTG_CALL getParameterData(int32_t) override { return nullptr; }
    void* SMTG_CALL addParameterData(const ParamID&, int32_t&) override { return nullptr; }
};

struct ProcessData {
    int32_t processMode;
    int32_t symbolicSampleSize;
    int32_t numSamples;
    int32_t numInputs;
    int32_t numOutputs;
    AudioBusBuffers* inputs;
    AudioBusBuffers* outputs;
    IParameterChanges* inputParameterChanges;
    IParameterChanges* outputParameterChanges;
    void* inputEvents;
    void* outputEvents;
    ProcessContext* processContext;
};

class IAudioProcessor : public FUnknown {
public:
    virtual tresult SMTG_CALL setBusArrangements(uint64_t* inputs, int32_t numIns, uint64_t* outputs, int32_t numOuts) = 0;
    virtual tresult SMTG_CALL getBusArrangement(int32_t dir, int32_t index, uint64_t& arr) = 0;
    virtual tresult SMTG_CALL canProcessSampleSize(int32_t symbolicSampleSize) = 0;
    virtual uint32_t SMTG_CALL getLatencySamples() = 0;
    virtual tresult SMTG_CALL setupProcessing(ProcessSetup& setup) = 0;
    virtual tresult SMTG_CALL setProcessing(uint8_t state) = 0;
    virtual tresult SMTG_CALL process(ProcessData& data) = 0;
    virtual uint32_t SMTG_CALL getTailSamples() = 0;
};

struct ParameterInfo {
    ParamID id;
    char16 title[128];
    char16 shortTitle[128];
    char16 units[128];
    int32_t stepCount;
    ParamValue defaultNormalizedValue;
    int32_t unitId;
    int32_t flags;
};

struct ViewRect {
    int32_t left;
    int32_t top;
    int32_t right;
    int32_t bottom;
};

class IPlugFrame : public FUnknown {
public:
    virtual tresult SMTG_CALL resizeView(void* view, ViewRect* newSize) = 0;
};

class IPlugView : public FUnknown {
public:
    virtual tresult SMTG_CALL isPlatformTypeSupported(const char* type) = 0;
    virtual tresult SMTG_CALL attached(void* parent, const char* type) = 0;
    virtual tresult SMTG_CALL removed() = 0;
    virtual tresult SMTG_CALL onWheel(float distance) = 0;
    virtual tresult SMTG_CALL onKeyDown(char16 key, int16_t keyCode, int16_t modifiers) = 0;
    virtual tresult SMTG_CALL onKeyUp(char16 key, int16_t keyCode, int16_t modifiers) = 0;
    virtual tresult SMTG_CALL getSize(ViewRect* size) = 0;
    virtual tresult SMTG_CALL onSize(ViewRect* newSize) = 0;
    virtual tresult SMTG_CALL onFocus(uint8_t state) = 0;
    virtual tresult SMTG_CALL setFrame(IPlugFrame* frame) = 0;
    virtual tresult SMTG_CALL canResize() = 0;
    virtual tresult SMTG_CALL checkSizeConstraint(ViewRect* rect) = 0;
};

class IComponentHandler : public FUnknown {
public:
    virtual tresult SMTG_CALL beginEdit(ParamID id) = 0;
    virtual tresult SMTG_CALL performEdit(ParamID id, ParamValue valueNormalized) = 0;
    virtual tresult SMTG_CALL endEdit(ParamID id) = 0;
    virtual tresult SMTG_CALL restartComponent(int32_t flags) = 0;
};

class IEditController : public IPluginBase {
public:
    virtual tresult SMTG_CALL setComponentState(void* state) = 0;
    virtual tresult SMTG_CALL setState(void* state) = 0;
    virtual tresult SMTG_CALL getState(void* state) = 0;
    virtual int32_t SMTG_CALL getParameterCount() = 0;
    virtual tresult SMTG_CALL getParameterInfo(int32_t paramIndex, ParameterInfo& info) = 0;
    virtual tresult SMTG_CALL getParamStringByValue(ParamID id, ParamValue valueNormalized, char16 string[128]) = 0;
    virtual tresult SMTG_CALL getParamValueByString(ParamID id, char16* string, ParamValue& valueNormalized) = 0;
    virtual ParamValue SMTG_CALL normalizedParamToPlain(ParamID id, ParamValue valueNormalized) = 0;
    virtual ParamValue SMTG_CALL plainParamToNormalized(ParamID id, ParamValue plainValue) = 0;
    virtual ParamValue SMTG_CALL getParamNormalized(ParamID id) = 0;
    virtual tresult SMTG_CALL setParamNormalized(ParamID id, ParamValue value) = 0;
    virtual tresult SMTG_CALL setComponentHandler(IComponentHandler* handler) = 0;
    virtual IPlugView* SMTG_CALL createView(const char* name) = 0;
};

class IConnectionPoint : public FUnknown {
public:
    virtual tresult SMTG_CALL connect(IConnectionPoint* other) = 0;
    virtual tresult SMTG_CALL disconnect(IConnectionPoint* other) = 0;
    virtual tresult SMTG_CALL notify(void* message) = 0;
};

class IHostApplication : public FUnknown {
public:
    virtual tresult SMTG_CALL getName(char16 name[128]) = 0;
    virtual tresult SMTG_CALL createInstance(TUID cid, TUID iid, void** obj) = 0;
};

class SimpleHostApp final : public IHostApplication {
    std::atomic<uint32_t> refs_{1};
public:
    tresult SMTG_CALL queryInterface(const TUID iid, void** obj) override {
        if (!obj) return kResultFalse;
        if (uidEq(iid, kFUnknownIID) || uidEq(iid, kIHostApplicationIID)) {
            addRef();
            *obj = static_cast<IHostApplication*>(this);
            return kResultOk;
        }
        *obj = nullptr;
        return kNoInterface;
    }
    uint32_t SMTG_CALL addRef() override { return ++refs_; }
    uint32_t SMTG_CALL release() override { return --refs_; }
    tresult SMTG_CALL getName(char16 name[128]) override {
        const char* s = "NEKOPLAY";
        int i = 0;
        for (; s[i] && i < 127; ++i) name[i] = static_cast<char16>(s[i]);
        name[i] = 0;
        return kResultOk;
    }
    tresult SMTG_CALL createInstance(TUID, TUID, void** obj) override {
        if (obj) *obj = nullptr;
        return kNotImplemented;
    }
};

class SimpleComponentHandler final : public IComponentHandler {
    std::atomic<uint32_t> refs_{1};
public:
    tresult SMTG_CALL queryInterface(const TUID iid, void** obj) override {
        if (!obj) return kResultFalse;
        if (uidEq(iid, kFUnknownIID) || uidEq(iid, kIComponentHandlerIID)) {
            addRef();
            *obj = static_cast<IComponentHandler*>(this);
            return kResultOk;
        }
        *obj = nullptr;
        return kNoInterface;
    }
    uint32_t SMTG_CALL addRef() override { return ++refs_; }
    uint32_t SMTG_CALL release() override { return --refs_; }
    tresult SMTG_CALL beginEdit(ParamID) override { return kResultOk; }
    tresult SMTG_CALL performEdit(ParamID, ParamValue) override { return kResultOk; }
    tresult SMTG_CALL endEdit(ParamID) override { return kResultOk; }
    tresult SMTG_CALL restartComponent(int32_t) override { return kResultOk; }
};

class SimplePlugFrame final : public IPlugFrame {
    std::atomic<uint32_t> refs_{1};
public:
    void** winPtr = nullptr;
    tresult SMTG_CALL queryInterface(const TUID iid, void** obj) override {
        if (!obj) return kResultFalse;
        if (uidEq(iid, kFUnknownIID) || uidEq(iid, kIPlugFrameIID)) {
            addRef();
            *obj = static_cast<IPlugFrame*>(this);
            return kResultOk;
        }
        *obj = nullptr;
        return kNoInterface;
    }
    uint32_t SMTG_CALL addRef() override { return ++refs_; }
    uint32_t SMTG_CALL release() override { return --refs_; }
    tresult SMTG_CALL resizeView(void* view, ViewRect* newSize) override {
        if (winPtr && *winPtr && newSize) {
            int w = newSize->right - newSize->left;
            int h = newSize->bottom - newSize->top;
            if (w > 40 && h > 40) nvGuiResizeWindow(*winPtr, w, h);
            if (view) static_cast<IPlugView*>(view)->onSize(newSize);
            return kResultOk;
        }
        return kResultFalse;
    }
};

using GetPluginFactoryFn = IPluginFactory*(SMTG_CALL*)();

class Vst3Instance final : public VstPluginInstance {
public:
    SimpleHostApp hostApp;
    SimpleComponentHandler compHandler;
    SimplePlugFrame plugFrame;
    EmptyParamChanges emptyParams_;
    ProcessContext processCtx_{};

    IPluginFactory* factory = nullptr;
    IComponent* component = nullptr;
    IAudioProcessor* processor = nullptr;
    IEditController* controller = nullptr;
    bool controllerIsComponent = false;
    IPlugView* view = nullptr;
    IPlugView* pendingView_ = nullptr;
    const char* pendingPlatType_ = nullptr;

    std::vector<float> outL_, outR_;
    std::mutex procMutex_;
    double sampleRate_ = 48000.0;
    int64_t samplePos_ = 0;
    int inBuses_ = 1;
    int outBuses_ = 1;
    bool active_ = false;

#ifdef _WIN32
    HMODULE mod = nullptr;
    using ExitDllFn = bool(SMTG_CALL*)();
    ExitDllFn exitDll = nullptr;
#elif defined(__APPLE__)
    CFBundleRef bundle = nullptr;
    using BundleExitFn = bool(*)();
    BundleExitFn bundleExit = nullptr;
#else
    void* dl = nullptr;
    using ModuleExitFn = bool(*)();
    ModuleExitFn moduleExit = nullptr;
#endif

    Vst3Instance() { plugFrame.winPtr = &guiHandle; }

    ~Vst3Instance() override {
        closeEditor();
        if (processor && active_) processor->setProcessing(0);
        if (component && active_) component->setActive(0);
        if (controller && !controllerIsComponent) {
            controller->terminate();
            controller->release();
        } else if (controller && controllerIsComponent) {
            controller->release();
        }
        if (processor) processor->release();
        if (component) {
            component->terminate();
            component->release();
        }
        if (factory) factory->release();
#ifdef _WIN32
        if (exitDll) exitDll();
        if (mod) FreeLibrary(mod);
#elif defined(__APPLE__)
        if (bundleExit) bundleExit();
        if (bundle) CFRelease(bundle);
#else
        if (moduleExit) moduleExit();
        if (dl) dlclose(dl);
#endif
    }

    bool prepare(double sampleRate, int maxBlockSize) override {
        std::lock_guard<std::mutex> lk(procMutex_);
        if (!component || !processor) return false;
        sampleRate_ = sampleRate;
        outL_.assign(static_cast<size_t>(maxBlockSize), 0.0f);
        outR_.assign(static_cast<size_t>(maxBlockSize), 0.0f);
        if (active_) {
            processor->setProcessing(0);
            component->setActive(0);
            active_ = false;
        }
        inBuses_ = std::max(0, component->getBusCount(0, 0));
        outBuses_ = std::max(0, component->getBusCount(0, 1));
        uint64_t stereo = 0x3; // kStereo (L | R)
        processor->setBusArrangements(
            inBuses_ > 0 ? &stereo : nullptr, inBuses_ > 0 ? 1 : 0,
            outBuses_ > 0 ? &stereo : nullptr, outBuses_ > 0 ? 1 : 0);
        ProcessSetup setup{};
        setup.processMode = 0; // kRealtime
        setup.symbolicSampleSize = 0; // kSample32
        setup.maxSamplesPerBlock = maxBlockSize;
        setup.sampleRate = sampleRate;
        if (processor->setupProcessing(setup) != kResultOk) {
            nvSetError("VST3 setupProcessing 失败");
            return false;
        }
        if (inBuses_ > 0) component->activateBus(0, 0, 0, 1); // Audio Input 0
        if (outBuses_ > 0) component->activateBus(0, 1, 0, 1); // Audio Output 0
        component->setActive(1);
        processor->setProcessing(1);
        processCtx_ = {};
        processCtx_.state = (1u << 1) | (1u << 10) | (1u << 11); // kPlaying | kTempoValid | kTimeSigValid
        processCtx_.sampleRate = sampleRate;
        processCtx_.tempo = 120.0;
        processCtx_.timeSigNumerator = 4;
        processCtx_.timeSigDenominator = 4;
        active_ = true;
        return true;
    }

    void process(float* left, float* right, int numFrames) override {
        if (!processor || !active_ || bypassed.load(std::memory_order_relaxed) || numFrames <= 0) return;
        std::unique_lock<std::mutex> lk(procMutex_, std::try_to_lock);
        if (!lk.owns_lock()) return;
        if (static_cast<int>(outL_.size()) < numFrames) {
            outL_.resize(static_cast<size_t>(numFrames));
            outR_.resize(static_cast<size_t>(numFrames));
        }
        std::memcpy(outL_.data(), left, static_cast<size_t>(numFrames) * sizeof(float));
        std::memcpy(outR_.data(), right, static_cast<size_t>(numFrames) * sizeof(float));
        float* inCh[2] = { left, right };
        float* outCh[2] = { outL_.data(), outR_.data() };
        AudioBusBuffers inBus{2, 0, {inCh}};
        AudioBusBuffers outBus{2, 0, {outCh}};
        processCtx_.sampleRate = sampleRate_;
        processCtx_.projectTimeSamples = samplePos_;
        samplePos_ += numFrames;
        ProcessData data{};
        data.processMode = 0;
        data.symbolicSampleSize = 0;
        data.numSamples = numFrames;
        data.numInputs = inBuses_ > 0 ? 1 : 0;
        data.numOutputs = outBuses_ > 0 ? 1 : 0;
        data.inputs = inBuses_ > 0 ? &inBus : nullptr;
        data.outputs = outBuses_ > 0 ? &outBus : nullptr;
        data.inputParameterChanges = &emptyParams_;
        data.outputParameterChanges = &emptyParams_;
        data.processContext = &processCtx_;
        if (processor->process(data) == kResultOk && outBuses_ > 0) {
            std::memcpy(left, outL_.data(), static_cast<size_t>(numFrames) * sizeof(float));
            std::memcpy(right, outR_.data(), static_cast<size_t>(numFrames) * sizeof(float));
        }
    }

    std::vector<VstParamInfo> getParams() override {
        std::vector<VstParamInfo> res;
        if (!controller) return res;
        int n = std::min(controller->getParameterCount(), 256);
        for (int i = 0; i < n; ++i) {
            ParameterInfo pi{};
            if (controller->getParameterInfo(i, pi) != kResultOk) continue;
            if (pi.flags & (1 << 3)) continue; // skip kIsReadOnly? keep visible params
            ParamValue val = controller->getParamNormalized(pi.id);
            char16 disp[128] = {0};
            controller->getParamStringByValue(pi.id, val, disp);
            VstParamInfo out;
            out.index = i;
            out.id = pi.id;
            out.name = utf16ToUtf8(pi.title, 128);
            if (out.name.empty()) out.name = "Param " + std::to_string(i);
            out.value = static_cast<float>(val);
            out.display = utf16ToUtf8(disp, 128);
            res.push_back(std::move(out));
        }
        return res;
    }

    bool setParam(int index, float value) override {
        if (!controller) return false;
        ParameterInfo pi{};
        if (controller->getParameterInfo(index, pi) != kResultOk) return false;
        controller->setParamNormalized(pi.id, std::clamp(static_cast<double>(value), 0.0, 1.0));
        return true;
    }

    static bool onEditorAttach(void* ud, void* nativeView) {
        auto* self = static_cast<Vst3Instance*>(ud);
        if (!self || !self->pendingView_ || !nativeView) return false;
        IPlugView* v = self->pendingView_;
        v->setFrame(&self->plugFrame);
        if (v->attached(nativeView, self->pendingPlatType_) != kResultOk) {
            v->setFrame(nullptr);
            return false;
        }
        self->view = v;
        return true;
    }

    static void onEditorClose(void* ud) {
        auto* self = static_cast<Vst3Instance*>(ud);
        if (!self) return;
        if (self->view) {
            self->view->removed();
            self->view->setFrame(nullptr);
            self->view->release();
            self->view = nullptr;
        }
        self->guiHandle = nullptr;
    }

    bool openEditor(bool alwaysOnTop) override {
        if (!controller) {
            nvSetError("该 VST3 插件没有提供控制器界面");
            return false;
        }
        if (guiHandle) {
            nvGuiSetOnTop(guiHandle, alwaysOnTop);
            return true;
        }
        IPlugView* v = controller->createView("editor");
        if (!v) {
            nvSetError("该 VST3 插件不提供原生界面");
            return false;
        }
#ifdef _WIN32
        const char* platType = "HWND";
#elif defined(__APPLE__)
        const char* platType = "NSView";
#else
        const char* platType = "X11EmbedWindowID";
#endif
        if (v->isPlatformTypeSupported(platType) != kResultOk) {
            v->release();
            nvSetError("该 VST3 插件界面不支持当前平台窗口类型");
            return false;
        }
        ViewRect vr{0, 0, 640, 420};
        v->getSize(&vr);
        int w = (vr.right > vr.left) ? (vr.right - vr.left) : 640;
        int h = (vr.bottom > vr.top) ? (vr.bottom - vr.top) : 420;
        pendingView_ = v;
        pendingPlatType_ = platType;
        EditorCallbacks cb;
        cb.userData = this;
        cb.onAttach = &Vst3Instance::onEditorAttach;
        cb.onClose = &Vst3Instance::onEditorClose;
        void* nativeView = nullptr;
        guiHandle = nvGuiCreateWindow(name.c_str(), w, h, alwaysOnTop, cb, &nativeView);
        pendingView_ = nullptr;
        pendingPlatType_ = nullptr;
        if (!guiHandle || !view) {
            if (!view) v->release();
            guiHandle = nullptr;
            nvSetError("创建或挂载 VST3 插件界面失败");
            return false;
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

#ifdef _WIN32
std::string resolveVst3BinaryWin(const std::string& bundlePath) {
    namespace fs = std::filesystem;
    fs::path p(bundlePath);
    if (fs::is_regular_file(p)) return bundlePath;
    fs::path dir = p / "Contents" / "x86_64-win";
    if (fs::is_directory(dir)) {
        for (auto& e : fs::directory_iterator(dir)) {
            if (e.is_regular_file() && e.path().extension() == ".vst3") return e.path().string();
        }
    }
    return bundlePath;
}
#endif

} // namespace

std::unique_ptr<VstPluginInstance> loadVst3Plugin(const std::string& path) {
    auto inst = std::make_unique<Vst3Instance>();
    inst->path = path;
    inst->format = "VST3";
    inst->name = std::filesystem::path(path).stem().string();

    GetPluginFactoryFn getFactory = nullptr;

#ifdef _WIN32
    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    std::string binPath = resolveVst3BinaryWin(path);
    int wlen = MultiByteToWideChar(CP_UTF8, 0, binPath.c_str(), -1, nullptr, 0);
    std::wstring wpath(static_cast<size_t>(wlen), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, binPath.c_str(), -1, wpath.data(), wlen);
    inst->mod = LoadLibraryW(wpath.c_str());
    if (!inst->mod) {
        nvSetError("无法加载 VST3 模块：" + binPath);
        return nullptr;
    }
    using InitDllFn = bool(SMTG_CALL*)();
    auto initDll = reinterpret_cast<InitDllFn>(GetProcAddress(inst->mod, "InitDll"));
    if (initDll) initDll();
    inst->exitDll = reinterpret_cast<Vst3Instance::ExitDllFn>(GetProcAddress(inst->mod, "ExitDll"));
    getFactory = reinterpret_cast<GetPluginFactoryFn>(GetProcAddress(inst->mod, "GetPluginFactory"));
#elif defined(__APPLE__)
    CFStringRef cfPath = CFStringCreateWithCString(kCFAllocatorDefault, path.c_str(), kCFStringEncodingUTF8);
    CFURLRef url = CFURLCreateWithFileSystemPath(kCFAllocatorDefault, cfPath, kCFURLPOSIXPathStyle, true);
    CFRelease(cfPath);
    if (url) {
        inst->bundle = CFBundleCreate(kCFAllocatorDefault, url);
        CFRelease(url);
    }
    if (!inst->bundle || !CFBundleLoadExecutable(inst->bundle)) {
        nvSetError("无法加载 macOS VST3 Bundle：" + path);
        return nullptr;
    }
    using BundleEntryFn = bool(*)(CFBundleRef);
    auto entry = reinterpret_cast<BundleEntryFn>(CFBundleGetFunctionPointerForName(inst->bundle, CFSTR("bundleEntry")));
    if (entry) entry(inst->bundle);
    inst->bundleExit = reinterpret_cast<Vst3Instance::BundleExitFn>(CFBundleGetFunctionPointerForName(inst->bundle, CFSTR("bundleExit")));
    getFactory = reinterpret_cast<GetPluginFactoryFn>(CFBundleGetFunctionPointerForName(inst->bundle, CFSTR("GetPluginFactory")));
#else
    namespace fs = std::filesystem;
    std::string binPath = path;
    fs::path dir = fs::path(path) / "Contents" / "x86_64-linux";
    if (fs::is_directory(dir)) {
        for (auto& e : fs::directory_iterator(dir)) {
            if (e.is_regular_file() && e.path().extension() == ".so") { binPath = e.path().string(); break; }
        }
    }
    inst->dl = dlopen(binPath.c_str(), RTLD_LAZY);
    if (!inst->dl) {
        nvSetError("无法加载 Linux VST3 模块：" + binPath);
        return nullptr;
    }
    using ModuleEntryFn = bool(*)(void*);
    auto entry = reinterpret_cast<ModuleEntryFn>(dlsym(inst->dl, "ModuleEntry"));
    if (entry) entry(inst->dl);
    inst->moduleExit = reinterpret_cast<Vst3Instance::ModuleExitFn>(dlsym(inst->dl, "ModuleExit"));
    getFactory = reinterpret_cast<GetPluginFactoryFn>(dlsym(inst->dl, "GetPluginFactory"));
#endif

    if (!getFactory) {
        nvSetError("不是有效的 VST3 插件（未找到 GetPluginFactory）：" + path);
        return nullptr;
    }
    inst->factory = getFactory();
    if (!inst->factory) {
        nvSetError("GetPluginFactory 返回空：" + path);
        return nullptr;
    }

    int32_t n = inst->factory->countClasses();
    PClassInfo chosen{};
    bool found = false;
    for (int32_t i = 0; i < n; ++i) {
        PClassInfo ci{};
        if (inst->factory->getClassInfo(i, &ci) == kResultOk && std::strcmp(ci.category, "Audio Module Class") == 0) {
            chosen = ci;
            found = true;
            break;
        }
    }
    if (!found) {
        nvSetError("VST3 中未找到 Audio Module Class：" + path);
        return nullptr;
    }
    if (chosen.name[0]) inst->name = chosen.name;

    void* compObj = nullptr;
    if (inst->factory->createInstance(chosen.cid, kIComponentIID, &compObj) != kResultOk || !compObj) {
        nvSetError("创建 VST3 IComponent 失败：" + inst->name);
        return nullptr;
    }
    inst->component = static_cast<IComponent*>(compObj);
    inst->component->initialize(&inst->hostApp);

    void* procObj = nullptr;
    if (inst->component->queryInterface(kIAudioProcessorIID, &procObj) != kResultOk || !procObj) {
        nvSetError("该 VST3 组件不支持 IAudioProcessor：" + inst->name);
        return nullptr;
    }
    inst->processor = static_cast<IAudioProcessor*>(procObj);

    // Controller: single-component or split-component
    void* ctrlObj = nullptr;
    if (inst->component->queryInterface(kIEditControllerIID, &ctrlObj) == kResultOk && ctrlObj) {
        inst->controller = static_cast<IEditController*>(ctrlObj);
        inst->controllerIsComponent = true;
        inst->controller->setComponentHandler(&inst->compHandler);
    } else {
        TUID ctrlCid{};
        if (inst->component->getControllerClassId(ctrlCid) == kResultOk) {
            if (inst->factory->createInstance(ctrlCid, kIEditControllerIID, &ctrlObj) == kResultOk && ctrlObj) {
                inst->controller = static_cast<IEditController*>(ctrlObj);
                inst->controller->initialize(&inst->hostApp);
                inst->controller->setComponentHandler(&inst->compHandler);
                // Connect component <-> controller via IConnectionPoint if supported
                void* cp1 = nullptr;
                void* cp2 = nullptr;
                if (inst->component->queryInterface(kIConnectionPointIID, &cp1) == kResultOk &&
                    inst->controller->queryInterface(kIConnectionPointIID, &cp2) == kResultOk && cp1 && cp2) {
                    static_cast<IConnectionPoint*>(cp1)->connect(static_cast<IConnectionPoint*>(cp2));
                    static_cast<IConnectionPoint*>(cp2)->connect(static_cast<IConnectionPoint*>(cp1));
                }
                if (cp1) static_cast<FUnknown*>(cp1)->release();
                if (cp2) static_cast<FUnknown*>(cp2)->release();
            }
        }
    }
    return inst;
}
