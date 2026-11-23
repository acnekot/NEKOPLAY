#pragma once

#include <atomic>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

struct VstParamInfo {
    int index = 0;
    uint32_t id = 0;
    std::string name;
    float value = 0.0f;
    std::string display;
};

class VstPluginInstance {
public:
    int slotId = -1;
    std::string path;
    std::string name;
    std::string format; // "VST3" or "VST2"
    std::atomic<bool> bypassed{false};
    void* guiHandle = nullptr;

    virtual ~VstPluginInstance() = default;
    virtual bool prepare(double sampleRate, int maxBlockSize) = 0;
    virtual void process(float* left, float* right, int numFrames) = 0;
    virtual std::vector<VstParamInfo> getParams() = 0;
    virtual bool setParam(int index, float value) = 0;
    virtual bool openEditor(bool alwaysOnTop) = 0;
    virtual void setEditorOnTop(bool alwaysOnTop) = 0;
    virtual void closeEditor() = 0;
};

// Lock-free SPSC ring buffer for float PCM samples
class RingBuffer {
public:
    explicit RingBuffer(size_t capacityPow2 = 131072) : mask_(capacityPow2 - 1), buf_(capacityPow2, 0.0f) {}

    void clear() {
        head_.store(0, std::memory_order_relaxed);
        tail_.store(0, std::memory_order_relaxed);
    }

    size_t write(const float* data, size_t count) {
        size_t h = head_.load(std::memory_order_relaxed);
        size_t t = tail_.load(std::memory_order_acquire);
        size_t avail = buf_.size() - (h - t);
        size_t n = count < avail ? count : avail;
        for (size_t i = 0; i < n; ++i) buf_[(h + i) & mask_] = data[i];
        head_.store(h + n, std::memory_order_release);
        return n;
    }

    size_t read(float* out, size_t count) {
        size_t t = tail_.load(std::memory_order_relaxed);
        size_t h = head_.load(std::memory_order_acquire);
        size_t avail = h - t;
        size_t n = count < avail ? count : avail;
        for (size_t i = 0; i < n; ++i) out[i] = buf_[(t + i) & mask_];
        tail_.store(t + n, std::memory_order_release);
        return n;
    }

    size_t availableRead() const {
        return head_.load(std::memory_order_acquire) - tail_.load(std::memory_order_relaxed);
    }

private:
    size_t mask_;
    std::vector<float> buf_;
    std::atomic<size_t> head_{0};
    std::atomic<size_t> tail_{0};
};

// Shared helpers
void nvSetError(const std::string& err);
std::string nvGetError();
std::string jsonEscape(const std::string& s);
char* dupStr(const std::string& s);

// Factory functions implemented in vst2_host.cpp and vst3_host.cpp
std::unique_ptr<VstPluginInstance> loadVst2Plugin(const std::string& path);
std::unique_ptr<VstPluginInstance> loadVst3Plugin(const std::string& path);

// Platform GUI helpers implemented in gui_mac.mm / gui_win.cpp / gui_linux.cpp
struct EditorCallbacks {
    void* userData = nullptr;
    bool (*onAttach)(void* userData, void* nativeView) = nullptr;
    void (*onIdle)(void* userData) = nullptr;
    void (*onClose)(void* userData) = nullptr;
};

void* nvGuiCreateWindow(const char* title, int width, int height, bool alwaysOnTop, const EditorCallbacks& cb, void** outNativeView);
void nvGuiResizeWindow(void* winHandle, int width, int height);
void nvGuiSetOnTop(void* winHandle, bool alwaysOnTop);
void nvGuiDestroyWindow(void* winHandle);
