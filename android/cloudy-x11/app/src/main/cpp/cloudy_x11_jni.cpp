#include <jni.h>
#include <android/looper.h>
#include <android/log.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cstdlib>
#include <string>

#include "embedded.h"

#define LOG_TAG "CloudyX11Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM* g_vm = nullptr;
static jobject g_callback = nullptr;
static jmethodID g_ready = nullptr;
static jmethodID g_window_callback = nullptr;
static bool g_started = false;
static std::string g_tmp_store;
static std::string g_xkb_store;
static std::string g_auth_store;
static std::string g_screen_store;

static void send_ready(const char* display) {
    if (!g_vm || !g_callback || !g_ready) return;
    JNIEnv* env = nullptr;
    bool detach = false;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        detach = true;
    }
    jstring text = env->NewStringUTF(display ? display : "");
    env->CallVoidMethod(g_callback, g_ready, text);
    env->DeleteLocalRef(text);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (detach) g_vm->DetachCurrentThread();
}

static void on_ready(void*, const char* display) {
    LOGI("native X11 ready display=%s", display ? display : "");
    send_ready(display);
}

static void on_window_size(const LorieWindowConstraints* constraints,
                           int* width, int* height) {
    // Minimal host layout policy for the bootstrap. The per-window host will
    // replace this with content-bounds and WM_NORMAL_HINTS policy.
    if (constraints) {
        if (*width < constraints->minWidth) *width = constraints->minWidth;
        if (*height < constraints->minHeight) *height = constraints->minHeight;
        if (*width > constraints->maxWidth) *width = constraints->maxWidth;
        if (*height > constraints->maxHeight) *height = constraints->maxHeight;
    }
}

extern "C" void cloudy_x11_window_event(uint32_t id, const LorieWindowInfo* info) {
    if (!g_vm || !g_callback || !g_window_callback) return;
    JNIEnv* env = nullptr;
    bool detach = false;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        detach = true;
    }
    jstring title = info && info->title ? env->NewStringUTF(info->title) : nullptr;
    env->CallVoidMethod(g_callback, g_window_callback, static_cast<jint>(id),
        title, static_cast<jint>(info ? info->role : -1),
        static_cast<jint>(info ? info->parent : 0),
        static_cast<jint>(info ? info->width : 0),
        static_cast<jint>(info ? info->height : 0),
        info && info->mapped ? JNI_TRUE : JNI_FALSE);
    if (title) env->DeleteLocalRef(title);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (detach) g_vm->DetachCurrentThread();
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_cloudy_x11_native_X11Native_start(
        JNIEnv* env, jobject,
        jstring tmp_dir, jstring xkb_root, jstring auth_file,
        jint width, jint height, jobject callback) {
    if (g_started) {
        LOGE("server already started in this process");
        return JNI_FALSE;
    }
    const char* tmp = env->GetStringUTFChars(tmp_dir, nullptr);
    const char* xkb = env->GetStringUTFChars(xkb_root, nullptr);
    const char* auth = env->GetStringUTFChars(auth_file, nullptr);
    if (!tmp || !xkb || !auth) {
        LOGE("null path");
        return JNI_FALSE;
    }
    g_tmp_store = tmp;
    g_xkb_store = xkb;
    g_auth_store = auth;
    setenv("TMPDIR", g_tmp_store.c_str(), 1);
    setenv("XKB_CONFIG_ROOT", g_xkb_store.c_str(), 1);

    if (!callback) {
        LOGE("missing ready callback");
        return JNI_FALSE;
    }
    if (g_callback) env->DeleteGlobalRef(g_callback);
    g_callback = env->NewGlobalRef(callback);
    jclass cls = env->GetObjectClass(callback);
    g_ready = env->GetMethodID(cls, "onNativeReady", "(Ljava/lang/String;)V");
    g_window_callback = env->GetMethodID(cls, "onNativeWindow",
        "(ILjava/lang/String;IIIIZ)V");
    env->DeleteLocalRef(cls);
    if (!g_ready) {
        LOGE("callback is missing onNativeReady(String)");
        return JNI_FALSE;
    }

    // The pinned parser accepts the smoke-proven form. Root/window geometry
    // is supplied by the renderer/window host, not a generic Xorg -screen arg.
    // Software-only presentation: FD-backed pixels, CPU Present copies, and no
    // DRI3/AHB imports. The dedicated-application EWMH bridge owns WM_S0.
    g_screen_store = std::to_string(width) + "x" +
        std::to_string(height) + "x24";
    setenv("MAGICDESK_X11_HOST_WM", "1", 1);
    const char* args[] = {
        ":99", "-noreset", "-nolisten", "tcp",
        "-auth", g_auth_store.c_str(),
        "-legacy-drawing", "-disable-gpu-present", "-disable-dri3",
    };
    LOGI("start args tmp=%s xkb=%s auth=%s requested=%s",
         g_tmp_store.c_str(), g_xkb_store.c_str(),
         g_auth_store.c_str(), g_screen_store.c_str());
    ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS);
    const LorieServerCallbacks callbacks = {
        .ready = on_ready,
        .windowSize = on_window_size,
    };
    bool ok = lorieServerStart(sizeof(args) / sizeof(args[0]), args,
                              &callbacks, nullptr);
    env->ReleaseStringUTFChars(tmp_dir, tmp);
    env->ReleaseStringUTFChars(xkb_root, xkb);
    env->ReleaseStringUTFChars(auth_file, auth);
    if (!ok) {
        LOGE("lorieServerStart returned false");
        return JNI_FALSE;
    }
    g_started = true;
    LOGI("lorieServerStart returned true");
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cloudy_x11_native_X11Native_stop(JNIEnv*, jobject) {
    if (!g_started) return;
    LOGI("requesting server stop");
    lorieServerStop();
    g_started = false;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cloudy_x11_native_X11Native_acceptFd(JNIEnv*, jobject, jint fd) {
    if (fd < 0) return;
    LOGI("accepting connected X11 fd=%d", fd);
    lorieServerAcceptClient(fd);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_cloudy_x11_native_X11Native_acceptSelfTest(JNIEnv*, jobject) {
    int pair[2] = {-1, -1};
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, pair) != 0) return -1;
    LOGI("accept self-test fd=%d", pair[1]);
    lorieServerAcceptClient(pair[1]);
    close(pair[0]);
    return pair[1];
}
