// Software-only LorieGraphics host and renderer connection control for Cloudy-X11.
//
// The X server engine calls this function table from its single renderer
// worker thread. The JNI control entry points run on the service's prepared
// HandlerThread Looper, which is also the connection Looper.

#include <jni.h>
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <new>
#include <utility>

#include "embedded.h"

#define LOG_TAG "CloudyX11Renderer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr uint32_t kBufferMagic = 0x4C425546;   // 'LBUF'
constexpr uint32_t kSurfaceMagic = 0x4C535546;  // 'LSUF'
constexpr uint32_t kOutputId = 1;
// The engine defines this HAL value itself; the NDK header omits it.
constexpr unsigned kFormatBgra8888Unorm = 5;

enum BufferKind : uint32_t {
    BUFFER_STAGING = 1,
    BUFFER_IMAGE = 2,
};

struct HostSurface;

struct Buffer {
    uint32_t magic = kBufferMagic;
    uint32_t kind = BUFFER_STAGING;
    uint8_t* pixels = nullptr;
    unsigned width = 0;
    unsigned height = 0;
    size_t stride = 0;          // bytes per row
    unsigned format = 0;        // AHARDWAREBUFFER_FORMAT_*
    HostSurface* owner = nullptr;
};

struct HostSurface {
    uint32_t magic = kSurfaceMagic;
    ANativeWindow* window = nullptr;
    Buffer* target = nullptr;
};

struct Pass {
    Buffer* target = nullptr;
};

constexpr uint32_t kDeviceMagic = 0x4C445646;  // 'LDVF'

struct Device {
    uint32_t magic = kDeviceMagic;
};

// Returns byte offsets for R, G and B in the nominal 32-bit layout. The alpha
// byte is always the high byte; RGBX labels may still carry depth-32 alpha, so
// blend passes read it without rewriting the nominal format.
bool channelOffsets(unsigned format, int* r, int* g, int* b, int* a) {
    switch (format) {
        case AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM:
            *r = 0; *g = 1; *b = 2; *a = 3;
            return true;
        case AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM:
            *r = 0; *g = 1; *b = 2; *a = 3;
            return true;
        case kFormatBgra8888Unorm:
            *r = 2; *g = 1; *b = 0; *a = 3;
            return true;
        default:
            return false;
    }
}

inline uint32_t loadPixel(const uint8_t* address) {
    uint32_t value;
    std::memcpy(&value, address, sizeof(value));
    return value;
}

inline void storePixel(uint8_t* address, uint32_t value) {
    std::memcpy(address, &value, sizeof(value));
}

inline int byte(uint32_t value, int offset) {
    return static_cast<int>((value >> (offset * 8)) & 0xFFu);
}

void freeStaging(Buffer* buffer) {
    if (!buffer) return;
    std::free(buffer->pixels);
    buffer->pixels = nullptr;
    delete buffer;
}

void freeBuffer(Buffer* buffer) {
    if (!buffer) return;
    if (buffer->kind == BUFFER_STAGING) {
        freeStaging(buffer);
        return;
    }
    // Image pixels are borrowed from the engine and must never be freed.
    delete buffer;
}

void* graphicsCreate() {
    return new (std::nothrow) Device();
}

void graphicsDestroy(void* device) {
    auto* value = static_cast<Device*>(device);
    if (!value || value->magic != kDeviceMagic) return;
    value->magic = 0;
    delete value;
}

void* graphicsImage(void*, AHardwareBuffer* buffer, const void* pixels,
                    unsigned width, unsigned height, size_t stride,
                    unsigned androidFormat) {
    if (buffer) {
        // This host is explicitly software-only and does not import AHB pixels.
        LOGE("AHardwareBuffer image import rejected");
        return nullptr;
    }
    if (!pixels || width == 0 || height == 0) return nullptr;
    int r, g, b, a;
    if (!channelOffsets(androidFormat, &r, &g, &b, &a)) {
        LOGE("unsupported image format %u", androidFormat);
        return nullptr;
    }
    if (stride < static_cast<size_t>(width) * 4) return nullptr;

    auto* image = new (std::nothrow) Buffer();
    if (!image) return nullptr;
    image->kind = BUFFER_IMAGE;
    image->pixels = const_cast<uint8_t*>(static_cast<const uint8_t*>(pixels));
    image->width = width;
    image->height = height;
    image->stride = stride;
    image->format = androidFormat;
    return image;
}

void graphicsReleaseImage(void* handle) {
    auto* image = static_cast<Buffer*>(handle);
    if (!image || image->magic != kBufferMagic || image->kind != BUFFER_IMAGE) return;
    freeBuffer(image);
}

void* graphicsSurface(void*, ANativeWindow* window) {
    if (!window) return nullptr;
    LOGI("surface width=%d height=%d format=%d",
         ANativeWindow_getWidth(window), ANativeWindow_getHeight(window),
         ANativeWindow_getFormat(window));
    auto* surface = new (std::nothrow) HostSurface();
    if (!surface) return nullptr;
    surface->window = window;
    return surface;
}

void graphicsReleaseSurface(void* handle) {
    auto* surface = static_cast<HostSurface*>(handle);
    if (!surface || surface->magic != kSurfaceMagic) return;
    surface->magic = 0;
    if (surface->target) {
        freeStaging(surface->target);
        surface->target = nullptr;
    }
    // The engine owns the ANativeWindow reference until acknowledged release.
    delete surface;
}

void* graphicsAcquire(void* handle, unsigned width, unsigned height) {
    auto* surface = static_cast<HostSurface*>(handle);
    if (!surface || surface->magic != kSurfaceMagic || width == 0 || height == 0)
        return nullptr;
    if (surface->target) {
        freeStaging(surface->target);
        surface->target = nullptr;
    }
    if (height > SIZE_MAX / 4 / width) return nullptr;
    auto* target = new (std::nothrow) Buffer();
    if (!target) return nullptr;
    target->pixels = static_cast<uint8_t*>(std::calloc(
            static_cast<size_t>(width) * height, 4));
    if (!target->pixels) {
        delete target;
        return nullptr;
    }
    target->kind = BUFFER_STAGING;
    target->width = width;
    target->height = height;
    target->stride = static_cast<size_t>(width) * 4;
    target->format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    target->owner = surface;
    surface->target = target;
    return target;
}

void clearTarget(Buffer* target, const float clear[4]) {
    int r, g, b, a;
    if (!channelOffsets(target->format, &r, &g, &b, &a)) return;
    auto component = [](float value) {
        if (!std::isfinite(value)) return 0;
        long scaled = std::lround(value * 255.0f);
        if (scaled < 0) return 0;
        if (scaled > 255) return 255;
        return static_cast<int>(scaled);
    };
    const uint32_t pixel =
        (static_cast<uint32_t>(component(clear[0])) << (r * 8)) |
        (static_cast<uint32_t>(component(clear[1])) << (g * 8)) |
        (static_cast<uint32_t>(component(clear[2])) << (b * 8)) |
        (static_cast<uint32_t>(component(clear[3])) << (a * 8));
    for (unsigned y = 0; y < target->height; ++y) {
        uint8_t* row = target->pixels + static_cast<size_t>(y) * target->stride;
        for (unsigned x = 0; x < target->width; ++x)
            storePixel(row + static_cast<size_t>(x) * 4, pixel);
    }
}

void* graphicsBegin(void*, void* handle, const float clear[4], bool preserve) {
    auto* target = static_cast<Buffer*>(handle);
    if (!target || target->magic != kBufferMagic || !target->pixels) return nullptr;
    static int begins = 0;
    if (begins++ < 5)
        LOGI("begin target=%ux%u preserve=%d clear=%.2f/%.2f/%.2f/%.2f",
             target->width, target->height, preserve,
             clear ? clear[0] : -1, clear ? clear[1] : -1,
             clear ? clear[2] : -1, clear ? clear[3] : -1);
    if (!preserve) {
        if (!clear) return nullptr;
        clearTarget(target, clear);
    }
    auto* pass = new (std::nothrow) Pass();
    if (!pass) return nullptr;
    pass->target = target;
    return pass;
}

bool graphicsDraw(void* handle, const LorieGraphicsDraw* command) {
    auto* pass = static_cast<Pass*>(handle);
    if (!pass || !pass->target || !command || !command->image) return false;
    auto* source = static_cast<Buffer*>(command->image);
    Buffer* target = pass->target;
    static int draws = 0;
    if (draws++ < 5)
        LOGI("draw src=%ux%u dst=%.1f,%.1f %.1fx%.1f clip=%d,%d %dx%d blend=%d swap=%d",
             source->width, source->height, command->x, command->y,
             command->width, command->height, command->clipX, command->clipY,
             command->clipWidth, command->clipHeight, command->blend,
             command->swapRedBlue);
    if (source->magic != kBufferMagic || !source->pixels) return false;
    if (target->magic != kBufferMagic || !target->pixels) return false;
    if (!std::isfinite(command->sx) || !std::isfinite(command->sy) ||
        !std::isfinite(command->sw) || !std::isfinite(command->sh) ||
        !std::isfinite(command->x) || !std::isfinite(command->y) ||
        !std::isfinite(command->width) || !std::isfinite(command->height))
        return false;
    if (command->sw <= 0 || command->sh <= 0 ||
        command->width <= 0 || command->height <= 0)
        return true;

    int sr, sg, sb, sa, tr, tg, tb, ta;
    if (!channelOffsets(source->format, &sr, &sg, &sb, &sa) ||
        !channelOffsets(target->format, &tr, &tg, &tb, &ta))
        return false;

    int clipLeft = 0, clipTop = 0;
    int clipRight = static_cast<int>(target->width);
    int clipBottom = static_cast<int>(target->height);
    if (command->clipWidth > 0 && command->clipHeight > 0) {
        clipLeft = std::max(clipLeft, command->clipX);
        clipTop = std::max(clipTop, command->clipY);
        clipRight = std::min(clipRight, command->clipX + command->clipWidth);
        clipBottom = std::min(clipBottom, command->clipY + command->clipHeight);
    } else {
        return true;
    }

    const double left = command->x;
    const double top = command->y;
    const double right = left + command->width;
    const double bottom = top + command->height;
    int x0 = static_cast<int>(std::floor(left));
    int x1 = static_cast<int>(std::ceil(right));
    int y0 = static_cast<int>(std::floor(top));
    int y1 = static_cast<int>(std::ceil(bottom));
    if (x0 < clipLeft) x0 = clipLeft;
    if (y0 < clipTop) y0 = clipTop;
    if (x1 > clipRight) x1 = clipRight;
    if (y1 > clipBottom) y1 = clipBottom;
    if (x0 >= x1 || y0 >= y1) return true;

    for (int dy = y0; dy < y1; ++dy) {
        const double v = (static_cast<double>(dy) + 0.5 - top) / command->height;
        if (v < 0.0 || v >= 1.0) continue;
        int sy = static_cast<int>(std::floor(command->sy + v * command->sh));
        if (sy < 0) sy = 0;
        if (sy >= static_cast<int>(source->height)) sy = static_cast<int>(source->height) - 1;
        const uint8_t* sourceRow =
            source->pixels + static_cast<size_t>(sy) * source->stride;
        uint8_t* targetRow =
            target->pixels + static_cast<size_t>(dy) * target->stride;

        for (int dx = x0; dx < x1; ++dx) {
            const double u =
                (static_cast<double>(dx) + 0.5 - left) / command->width;
            if (u < 0.0 || u >= 1.0) continue;
            int sx = static_cast<int>(std::floor(command->sx + u * command->sw));
            if (sx < 0) sx = 0;
            if (sx >= static_cast<int>(source->width))
                sx = static_cast<int>(source->width) - 1;

            const uint32_t value =
                loadPixel(sourceRow + static_cast<size_t>(sx) * 4);
            int r = byte(value, sr);
            int g = byte(value, sg);
            int b = byte(value, sb);
            if (command->swapRedBlue) std::swap(r, b);

            int alpha = 255;
            if (command->blend) {
                alpha = byte(value, sa);
                const int inverse = 255 - alpha;
                const uint32_t destination =
                    loadPixel(targetRow + static_cast<size_t>(dx) * 4);
                r += (byte(destination, tr) * inverse + 127) / 255;
                g += (byte(destination, tg) * inverse + 127) / 255;
                b += (byte(destination, tb) * inverse + 127) / 255;
                const int destinationAlpha = byte(destination, ta);
                alpha += (destinationAlpha * inverse + 127) / 255;
                if (r > 255) r = 255;
                if (g > 255) g = 255;
                if (b > 255) b = 255;
                if (alpha > 255) alpha = 255;
            }
            const uint32_t output =
                (static_cast<uint32_t>(r) << (tr * 8)) |
                (static_cast<uint32_t>(g) << (tg * 8)) |
                (static_cast<uint32_t>(b) << (tb * 8)) |
                (static_cast<uint32_t>(alpha) << (ta * 8));
            storePixel(targetRow + static_cast<size_t>(dx) * 4, output);
        }
    }
    return true;
}

bool graphicsSubmit(void* handle) {
    auto* pass = static_cast<Pass*>(handle);
    if (!pass || !pass->target) return false;
    delete pass;
    return true;
}

void graphicsCancel(void* handle) {
    auto* pass = static_cast<Pass*>(handle);
    if (!pass) return;
    Buffer* target = pass->target;
    if (target && target->magic == kBufferMagic &&
        target->kind == BUFFER_STAGING) {
        HostSurface* owner = target->owner;
        if (owner && owner->target == target) owner->target = nullptr;
        freeStaging(target);
    }
    delete pass;
}

bool graphicsPresent(void* handle) {
    auto* surface = static_cast<HostSurface*>(handle);
    if (!surface || surface->magic != kSurfaceMagic || !surface->window) return false;
    Buffer* target = surface->target;
    if (!target || !target->pixels) return false;
    ANativeWindow_Buffer buffer{};
    int32_t result = ANativeWindow_lock(surface->window, &buffer, nullptr);
    static int presents = 0;
    if (presents++ < 5)
        LOGI("present lock=%d window=%dx%d fmt=%d target=%ux%u",
             result, ANativeWindow_getWidth(surface->window),
             ANativeWindow_getHeight(surface->window),
             ANativeWindow_getFormat(surface->window),
             target->width, target->height);
    if (result != 0) {
        LOGE("ANativeWindow_lock failed: %d", result);
        return false;
    }
    bool copied = false;
    if (buffer.format == WINDOW_FORMAT_RGBA_8888 && buffer.bits &&
        buffer.stride > 0 && buffer.width > 0 && buffer.height > 0) {
        const unsigned columns = std::min(
                target->width, static_cast<unsigned>(buffer.width));
        unsigned rows = std::min(
                target->height, static_cast<unsigned>(buffer.height));
        const size_t bytes = static_cast<size_t>(columns) * 4;
        auto* destination = static_cast<uint8_t*>(buffer.bits);
        for (unsigned y = 0; y < rows; ++y) {
            std::memcpy(destination + static_cast<size_t>(y) *
                                static_cast<size_t>(buffer.stride) * 4,
                        target->pixels + static_cast<size_t>(y) * target->stride,
                        bytes);
        }
        copied = true;
    } else {
        LOGE("unexpected locked buffer width=%d height=%d stride=%d format=%d",
             buffer.width, buffer.height, buffer.stride, buffer.format);
    }
    result = ANativeWindow_unlockAndPost(surface->window);

    unsigned nonBlack = 0, sampled = 0;
    for (unsigned y = 0; y < target->height; y += 23) {
        const uint32_t* row = reinterpret_cast<const uint32_t*>(
                target->pixels + static_cast<size_t>(y) * target->stride);
        for (unsigned x = 0; x < target->width; x += 31) {
            sampled++;
            if ((row[x] & 0x00ffffffu) != 0) nonBlack++;
        }
    }
    if (presents <= 5)
        LOGI("present copied=%d post=%d nonBlack=%u/%u target=%ux%u",
             copied, result, nonBlack, sampled, target->width, target->height);
    if (surface->target == target) surface->target = nullptr;
    freeStaging(target);
    return copied && result == 0;
}

const LorieGraphics kGraphics = {
    .create = graphicsCreate,
    .destroy = graphicsDestroy,
    .image = graphicsImage,
    .releaseImage = graphicsReleaseImage,
    .surface = graphicsSurface,
    .releaseSurface = graphicsReleaseSurface,
    .acquire = graphicsAcquire,
    .begin = graphicsBegin,
    .draw = graphicsDraw,
    .submit = graphicsSubmit,
    .cancel = graphicsCancel,
    .present = graphicsPresent,
};

LorieConnection* g_connection = nullptr;
std::atomic<uint32_t> g_first_window{0};
uint32_t g_bound_output = 0;
bool g_disconnected = false;

void onFrame(void*, uint32_t output, uint32_t window, int width, int height,
             bool available) {
    static int frames = 0;
    if (frames++ < 10)
        LOGI("frame output=%u window=0x%x %dx%d available=%d",
             output, window, width, height, available);
}

void onDisconnected(void*) {
    LOGI("renderer connection disconnected");
    g_disconnected = true;
}

void onWindow(void*, uint32_t id, const LorieWindowInfo* info) {
    if (!info || !info->mapped || g_first_window.load()) return;
    // The first mapped application top-level becomes the initial output content.
    // Transients, dialogs and splash windows keep their own host policy later.
    if (info->parent != 0) return;
    if (info->role != LORIE_WINDOW_APPLICATION &&
        info->role != LORIE_WINDOW_UNCLASSIFIED)
        return;
    g_first_window.store(id);
    LOGI("first mapped window xid=0x%x title=%s role=%d parent=0x%x %dx%d",
         id, info->title ? info->title : "", info->role, info->parent,
         info->width, info->height);
}

void onWindowsCommitted(void*) {
    static int commits = 0;
    if (commits++ < 10) LOGI("windows committed");
}

void onData(void*, int, int, uint32_t, uint32_t, uint32_t, uint32_t,
            int, int, const char*, int descriptor) {
    // The receiver owns a nonnegative descriptor even when it consumes nothing.
    if (descriptor >= 0) close(descriptor);
}

void onInspectionNode(void*, uint32_t, const LorieInspectionNode*) {}
void onInspectionDone(void*, uint32_t, const LorieInspectionResult*) {}
void onCursor(void*, uint32_t, uint32_t, const LorieCursorInfo*, const uint32_t*) {}
void onShell(void*, uint32_t, uint32_t, const LorieShellInfo*) {}
void onShellState(void*, uint32_t, bool) {}
void onFamily(void*, uint32_t, const LorieFamilyGeometry*) {}
void onPresented(void*, uint32_t output, uint32_t serial, bool success) {
    LOGI("presented output=%u serial=%u success=%d", output, serial, success);
}
void onWindowGesture(void*, uint32_t, unsigned) {}

const LorieCallbacks kCallbacks = {
    .frame = onFrame,
    .disconnected = onDisconnected,
    .window = onWindow,
    .windowsCommitted = onWindowsCommitted,
    .data = onData,
    .inspectionNode = onInspectionNode,
    .inspectionDone = onInspectionDone,
    .cursor = onCursor,
    .shell = onShell,
    .shellState = onShellState,
    .family = onFamily,
    .presented = onPresented,
    .windowGesture = onWindowGesture,
};

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_cloudy_x11_native_X11Native_connectRenderer(JNIEnv*, jobject) {
    if (g_connection) {
        if (!g_disconnected) return JNI_TRUE;
        // The peer disappeared: dispose the dead connection before replacing it.
        lorieConnectionDestroy(g_connection);
        g_connection = nullptr;
        g_bound_output = 0;
        g_first_window.store(0);
        g_disconnected = false;
    }
    int descriptor = lorieServerConnect();
    if (descriptor < 0) {
        LOGE("lorieServerConnect failed");
        return JNI_FALSE;
    }
    LorieConnection* connection =
        lorieConnectionCreate(&kCallbacks, nullptr, &kGraphics);
    if (!connection) {
        LOGE("lorieConnectionCreate failed");
        close(descriptor);
        return JNI_FALSE;
    }
    if (!lorieConnectionConnect(connection, descriptor)) {
        LOGE("lorieConnectionConnect failed");
        // Connect consumed the descriptor and cleans up the connection.
        lorieConnectionDestroy(connection);
        return JNI_FALSE;
    }
    g_connection = connection;
    g_disconnected = false;
    lorieObserveWindows(connection);
    LOGI("renderer connected");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_cloudy_x11_native_X11Native_bindSurface(
        JNIEnv* env, jobject, jobject surface, jint width, jint height) {
    if (!g_connection || g_disconnected) return JNI_FALSE;
    if (!surface) {
        if (g_bound_output) {
            lorieConnectionSurface(g_connection, g_bound_output, nullptr, false);
            g_bound_output = 0;
        }
        return JNI_TRUE;
    }
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        LOGE("ANativeWindow_fromSurface returned null");
        return JNI_FALSE;
    }

    if (g_bound_output) {
        // Release the previous renderer output before rebinding. The ordered
        // control path re-creates the output record with the new Surface.
        lorieConnectionSurface(g_connection, g_bound_output, nullptr, true);
        g_bound_output = 0;
    }

    // XID zero selects the whole screen; a nonzero XID selects that top-level.
    const uint32_t windowId = g_first_window.load();
    bool bound = lorieConnectionSurface(g_connection, kOutputId, window, false);
    if (bound) {
        lorieOutputBind(g_connection, kOutputId, windowId);
        if (width > 0 && height > 0)
            lorieOutputResize(g_connection, kOutputId, windowId,
                              static_cast<int>(width), static_cast<int>(height));
        g_bound_output = kOutputId;
    }
    // The engine retains its own reference; drop the JNI-side reference now.
    ANativeWindow_release(window);
    LOGI("bind surface output=%u window=0x%x size=%dx%d bound=%d",
         kOutputId, windowId, width, height, bound ? 1 : 0);
    return bound ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cloudy_x11_native_X11Native_unbindSurface(JNIEnv*, jobject) {
    if (!g_connection) return;
    if (g_bound_output) {
        lorieConnectionSurface(g_connection, g_bound_output, nullptr, false);
        g_bound_output = 0;
        LOGI("presentation detached");
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_cloudy_x11_native_X11Native_firstWindow(JNIEnv*, jobject) {
    return static_cast<jint>(g_first_window.load());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cloudy_x11_native_X11Native_destroyRenderer(JNIEnv*, jobject) {
    if (g_connection) {
        lorieConnectionDestroy(g_connection);
        g_connection = nullptr;
    }
    g_bound_output = 0;
    g_first_window = 0;
    g_disconnected = false;
}
