#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <atomic>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <map>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include "libretro.h"

namespace {
// Load/unload the core per session so legacy MAME globals cannot leak from one
// game into the next. The frontend itself remains loaded by Android's JVM.
struct Core {
    void* library = nullptr;
    decltype(&retro_set_environment) set_environment = nullptr;
    decltype(&retro_set_video_refresh) set_video_refresh = nullptr;
    decltype(&retro_set_audio_sample) set_audio_sample = nullptr;
    decltype(&retro_set_audio_sample_batch) set_audio_sample_batch = nullptr;
    decltype(&retro_set_input_poll) set_input_poll = nullptr;
    decltype(&retro_set_input_state) set_input_state = nullptr;
    decltype(&retro_init) init = nullptr;
    decltype(&retro_load_game) load_game = nullptr;
    decltype(&retro_get_system_av_info) get_system_av_info = nullptr;
    decltype(&retro_set_controller_port_device) set_controller_port_device = nullptr;
    decltype(&retro_run) run = nullptr;
    decltype(&retro_unload_game) unload_game = nullptr;
    decltype(&retro_deinit) deinit = nullptr;
    bool open() {
        library = dlopen("libretro.so", RTLD_NOW | RTLD_LOCAL);
        if (!library) return false;
#define LOAD(name) name = reinterpret_cast<decltype(name)>(dlsym(library, "retro_" #name)); if (!name) return false
        LOAD(set_environment); LOAD(set_video_refresh); LOAD(set_audio_sample);
        LOAD(set_audio_sample_batch); LOAD(set_input_poll); LOAD(set_input_state);
        LOAD(init); LOAD(load_game); LOAD(get_system_av_info);
        LOAD(set_controller_port_device); LOAD(run); LOAD(unload_game); LOAD(deinit);
#undef LOAD
        return true;
    }
    ~Core() { if (library) dlclose(library); }
};
std::atomic<bool> stopped{true}, paused{false};
std::atomic<unsigned> buttons{0};
std::mutex session;
JNIEnv* javaEnv = nullptr;
jobject engine = nullptr;
jmethodID audioMethod = nullptr;
ANativeWindow* window = nullptr;
std::string systemDir, saveDir, contentDir, lastError;
std::map<std::string, std::string> options;
unsigned pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555, rotation = 0;
float aspect = 0;
double fps = 60;
std::vector<int16_t> singleSamples;

void logMessage(enum retro_log_level level, const char* format, ...) {
    char message[2048];
    va_list args;
    va_start(args, format);
    vsnprintf(message, sizeof(message), format, args);
    va_end(args);
    __android_log_write(level >= RETRO_LOG_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO, "MameCore", message);
    if (level >= RETRO_LOG_ERROR && lastError.size() < 6000) lastError += message;
}

bool environment(unsigned cmd, void* data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
            static_cast<retro_log_callback*>(data)->log = logMessage; return true;
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: *static_cast<const char**>(data) = systemDir.c_str(); return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: *static_cast<const char**>(data) = saveDir.c_str(); return true;
        case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY: *static_cast<const char**>(data) = contentDir.c_str(); return true;
        case RETRO_ENVIRONMENT_GET_CAN_DUPE: *static_cast<bool*>(data) = true; return true;
        case RETRO_ENVIRONMENT_GET_LANGUAGE: *static_cast<unsigned*>(data) = RETRO_LANGUAGE_ENGLISH; return true;
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
            pixelFormat = *static_cast<unsigned*>(data); return pixelFormat <= RETRO_PIXEL_FORMAT_RGB565;
        case RETRO_ENVIRONMENT_SET_ROTATION: rotation = *static_cast<unsigned*>(data) % 4; return true;
        case RETRO_ENVIRONMENT_SET_GEOMETRY:
            aspect = static_cast<retro_game_geometry*>(data)->aspect_ratio; return true;
        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: {
            auto* av = static_cast<retro_system_av_info*>(data);
            aspect = av->geometry.aspect_ratio; fps = av->timing.fps; return true;
        }
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION: *static_cast<unsigned*>(data) = 2; return true;
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2: {
            auto* defs = static_cast<retro_core_options_v2*>(data)->definitions;
            for (auto* d = defs; d && d->key; ++d)
                options[d->key] = d->default_value ? d->default_value : d->values[0].value;
            // Keep original startup notices. Only joypad input is exposed in this first version.
            options["mame2003-plus_input_interface"] = "retropad";
            return true;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            auto* var = static_cast<retro_variable*>(data);
            auto it = options.find(var->key);
            var->value = it == options.end() ? nullptr : it->second.c_str();
            return it != options.end();
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: *static_cast<bool*>(data) = false; return true;
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL: return true;
        case RETRO_ENVIRONMENT_SHUTDOWN: stopped.store(true); return true;
        default: return false;
    }
}

void video(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (!data || !window || !width || !height) return;
    ANativeWindow_Buffer out;
    if (ANativeWindow_lock(window, &out, nullptr) != 0) return;
    auto* pixels = static_cast<uint32_t*>(out.bits);
    std::fill(pixels, pixels + out.stride * out.height, 0xff000000U);
    unsigned rw = rotation % 2 ? height : width;
    unsigned rh = rotation % 2 ? width : height;
    float ratio = aspect > 0 ? aspect : static_cast<float>(width) / height;
    if (rotation % 2) ratio = 1.f / ratio;
    int dw = std::min(out.width, static_cast<int>(out.height * ratio));
    int dh = std::min(out.height, static_cast<int>(out.width / ratio));
    int ox = (out.width - dw) / 2, oy = (out.height - dh) / 2;
    for (int y = 0; y < dh; ++y) {
        unsigned ry = static_cast<unsigned>(y) * rh / dh;
        for (int x = 0; x < dw; ++x) {
            unsigned rx = static_cast<unsigned>(x) * rw / dw, sx = rx, sy = ry;
            // libretro rotation is counterclockwise.
            if (rotation == 1) { sx = width - 1 - ry; sy = rx; }
            if (rotation == 2) { sx = width - 1 - rx; sy = height - 1 - ry; }
            if (rotation == 3) { sx = ry; sy = height - 1 - rx; }
            const auto* row = static_cast<const uint8_t*>(data) + sy * pitch;
            uint32_t r, g, b;
            if (pixelFormat == RETRO_PIXEL_FORMAT_XRGB8888) {
                uint32_t p = reinterpret_cast<const uint32_t*>(row)[sx];
                r = (p >> 16) & 255; g = (p >> 8) & 255; b = p & 255;
            } else {
                uint16_t p = reinterpret_cast<const uint16_t*>(row)[sx];
                bool rgb565 = pixelFormat == RETRO_PIXEL_FORMAT_RGB565;
                r = ((p >> (rgb565 ? 11 : 10)) & 31) * 255 / 31;
                g = ((p >> 5) & (rgb565 ? 63 : 31)) * 255 / (rgb565 ? 63 : 31);
                b = (p & 31) * 255 / 31;
            }
            pixels[(y + oy) * out.stride + x + ox] = 0xff000000U | (b << 16) | (g << 8) | r;
        }
    }
    ANativeWindow_unlockAndPost(window);
}

size_t audioBatch(const int16_t* data, size_t frames) {
    if (!frames || javaEnv->ExceptionCheck()) return frames;
    auto array = javaEnv->NewShortArray(static_cast<jsize>(frames * 2));
    if (!array) { stopped.store(true); return 0; }
    javaEnv->SetShortArrayRegion(array, 0, static_cast<jsize>(frames * 2), data);
    javaEnv->CallVoidMethod(engine, audioMethod, array);
    javaEnv->DeleteLocalRef(array);
    if (javaEnv->ExceptionCheck()) stopped.store(true);
    return frames;
}
void audioSample(int16_t left, int16_t right) { singleSamples.push_back(left); singleSamples.push_back(right); }
void pollInput() {}
int16_t input(unsigned port, unsigned device, unsigned, unsigned id) {
    if (port != 0 || device != RETRO_DEVICE_JOYPAD || id >= 16) return 0;
    return (buttons.load() >> id) & 1;
}
std::string utf(JNIEnv* env, jstring text) {
    const char* chars = env->GetStringUTFChars(text, nullptr);
    if (!chars) return {};
    std::string result(chars); env->ReleaseStringUTFChars(text, chars); return result;
}
}

extern "C" JNIEXPORT void JNICALL Java_com_taskreations_mametv_NativeEngine_nativePrepare(JNIEnv*, jobject) {
    stopped.store(false); paused.store(false); buttons.store(0);
}
extern "C" JNIEXPORT void JNICALL Java_com_taskreations_mametv_NativeEngine_nativeStop(JNIEnv*, jobject) { stopped.store(true); }
extern "C" JNIEXPORT void JNICALL Java_com_taskreations_mametv_NativeEngine_nativePause(JNIEnv*, jobject, jboolean value) { paused.store(value); buttons.store(0); }
extern "C" JNIEXPORT void JNICALL Java_com_taskreations_mametv_NativeEngine_nativeButtons(JNIEnv*, jobject, jint value) { buttons.store(static_cast<unsigned>(value)); }

extern "C" JNIEXPORT jstring JNICALL Java_com_taskreations_mametv_NativeEngine_run(
        JNIEnv* env, jobject self, jobject surface, jstring rom, jstring system, jstring save) {
    std::unique_lock<std::mutex> lock(session, std::try_to_lock);
    if (!lock.owns_lock()) return env->NewStringUTF("Another game is still closing. Try again.");
    Core core;
    if (!core.open()) return env->NewStringUTF("The embedded MAME core could not be loaded. Reinstall the complete APK for this TV's architecture.");
    javaEnv = env; engine = self;
    systemDir = utf(env, system); saveDir = utf(env, save);
    std::string path = utf(env, rom);
    contentDir = path.substr(0, path.find_last_of('/'));
    lastError.clear(); options.clear(); singleSamples.clear(); rotation = 0; aspect = 0; fps = 60;
    pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;
    window = ANativeWindow_fromSurface(env, surface);
    if (!window) return env->NewStringUTF("The TV display is unavailable.");
    // Let the compositor upscale a modest software framebuffer, preserving the
    // screen's shape without converting an entire 4K image on every arcade frame.
    int screenWidth = std::max(1, ANativeWindow_getWidth(window));
    int screenHeight = std::max(1, ANativeWindow_getHeight(window));
    int bufferWidth = std::min(screenWidth, 960);
    int bufferHeight = std::max(1, bufferWidth * screenHeight / screenWidth);
    ANativeWindow_setBuffersGeometry(window, bufferWidth, bufferHeight, WINDOW_FORMAT_RGBA_8888);
    jclass cls = env->GetObjectClass(self);
    audioMethod = env->GetMethodID(cls, "audio", "([S)V");
    jmethodID configure = env->GetMethodID(cls, "configureAudio", "(I)V");
    env->DeleteLocalRef(cls);
    core.set_environment(environment);
    core.set_video_refresh(video);
    core.set_audio_sample(audioSample);
    core.set_audio_sample_batch(audioBatch);
    core.set_input_poll(pollInput);
    core.set_input_state(input);
    core.init();
    retro_game_info game{path.c_str(), nullptr, 0, nullptr};
    bool loaded = core.load_game(&game);
    if (loaded) {
        retro_system_av_info av{}; core.get_system_av_info(&av);
        fps = av.timing.fps; aspect = av.geometry.aspect_ratio;
        env->CallVoidMethod(self, configure, static_cast<jint>(av.timing.sample_rate));
        core.set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
        auto next = std::chrono::steady_clock::now();
        while (!stopped.load() && !env->ExceptionCheck()) {
            if (paused.load()) { std::this_thread::sleep_for(std::chrono::milliseconds(20)); next = std::chrono::steady_clock::now(); continue; }
            core.run();
            if (!singleSamples.empty()) { audioBatch(singleSamples.data(), singleSamples.size() / 2); singleSamples.clear(); }
            double rate = std::isfinite(fps) && fps >= 1 && fps <= 240 ? fps : 60;
            next += std::chrono::nanoseconds(static_cast<int64_t>(1e9 / rate));
            std::this_thread::sleep_until(next);
            auto now = std::chrono::steady_clock::now();
            if (now - next > std::chrono::milliseconds(100)) next = now;
        }
        core.unload_game();
    }
    core.deinit();
    ANativeWindow_release(window); window = nullptr; engine = nullptr; javaEnv = nullptr;
    if (env->ExceptionCheck()) return nullptr;
    if (!loaded) {
        std::string reason = "Could not load this ROM set. Use the original ZIP name and a MAME2003-Plus compatible set. Parent/BIOS ZIPs must be imported alongside the game.\n\n" + lastError;
        return env->NewStringUTF(reason.c_str());
    }
    return nullptr;
}
