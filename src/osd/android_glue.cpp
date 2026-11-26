// Keisho — Android port glue.
// SPDX-License-Identifier: GPL-3.0-or-later

#include "osd/android_glue.h"

#if defined(__ANDROID__)

#include <SDL3/SDL.h>
#include <SDL3/SDL_system.h>

#include <android/log.h>
#include <jni.h>

#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <mutex>
#include <unistd.h>

namespace sm2::osd {

namespace {

std::FILE*  g_android_log = nullptr;
std::mutex  g_log_mutex;

std::mutex        g_pick_mutex;
AndroidPickResult g_pick_result = AndroidPickResult::None;
std::string       g_pick_path;

void log_line(const char* text)
{
    __android_log_write(ANDROID_LOG_INFO, "Keisho", text);
    std::lock_guard<std::mutex> lock(g_log_mutex);
    if (g_android_log == nullptr) {
        return;
    }
    char        stamp[32];
    std::time_t now = std::time(nullptr);
    std::strftime(stamp, sizeof stamp, "%H:%M:%S", std::localtime(&now));
    std::fprintf(g_android_log, "%s %s\n", stamp, text);
    std::fflush(g_android_log);
}

void logf(const char* format, ...)
{
    char    text[512];
    va_list args;
    va_start(args, format);
    std::vsnprintf(text, sizeof text, format, args);
    va_end(args);
    log_line(text);
}

void SDLCALL sdl_log_output(void*, int category, SDL_LogPriority priority, const char* message)
{
    logf("[SDL %d/%d] %s", category, static_cast<int>(priority), message);
}

const char* event_name(Uint32 type)
{
    switch (type) {
        case SDL_EVENT_WILL_ENTER_BACKGROUND:  return "will-enter-background";
        case SDL_EVENT_DID_ENTER_BACKGROUND:   return "did-enter-background";
        case SDL_EVENT_WILL_ENTER_FOREGROUND:  return "will-enter-foreground";
        case SDL_EVENT_DID_ENTER_FOREGROUND:   return "did-enter-foreground";
        case SDL_EVENT_RENDER_DEVICE_RESET:    return "render-device-reset (GL context recreated)";
        case SDL_EVENT_RENDER_TARGETS_RESET:   return "render-targets-reset";
        case SDL_EVENT_WINDOW_MINIMIZED:       return "window-minimized";
        case SDL_EVENT_WINDOW_RESTORED:        return "window-restored";
        case SDL_EVENT_WINDOW_RESIZED:         return "window-resized";
        case SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED: return "window-pixel-size-changed";
        case SDL_EVENT_WINDOW_FOCUS_GAINED:    return "focus-gained";
        case SDL_EVENT_WINDOW_FOCUS_LOST:      return "focus-lost";
        case SDL_EVENT_WINDOW_SHOWN:           return "window-shown";
        case SDL_EVENT_WINDOW_HIDDEN:          return "window-hidden";
        case SDL_EVENT_WINDOW_EXPOSED:         return "window-exposed";
        case SDL_EVENT_LOW_MEMORY:             return "low-memory";
        case SDL_EVENT_TERMINATING:            return "terminating";
        default:                               return nullptr;
    }
}

}  // namespace

void android_prepare()
{
    // Deliver Back to the program instead of letting Android close the app.
    SDL_SetHint(SDL_HINT_ANDROID_TRAP_BACK_BUTTON, "1");

    const char* base = SDL_GetAndroidExternalStoragePath();
    if (base == nullptr || base[0] == '\0') {
        base = SDL_GetAndroidInternalStoragePath();
    }
    if (base != nullptr && base[0] != '\0') {
        setenv("HOME", base, 1);
        if (chdir(base) != 0) {
            SDL_Log("keisho: chdir(%s) failed", base);
        }
        const std::string path = std::string(base) + "/android.log";
        g_android_log          = std::fopen(path.c_str(), "w");
    }
    SDL_SetLogOutputFunction(sdl_log_output, nullptr);
    logf("Keisho Android started (SDL %d.%d.%d)", SDL_VERSIONNUM_MAJOR(SDL_GetVersion()),
         SDL_VERSIONNUM_MINOR(SDL_GetVersion()), SDL_VERSIONNUM_MICRO(SDL_GetVersion()));
}

bool android_translate_event(SDL_Event* event)
{
    if (event == nullptr) {
        return true;
    }
    if (const char* name = event_name(event->type)) {
        if (event->type == SDL_EVENT_WINDOW_RESIZED
            || event->type == SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED) {
            logf("event: %s %dx%d", name, event->window.data1, event->window.data2);
        } else {
            logf("event: %s", name);
        }
        if (event->type == SDL_EVENT_DID_ENTER_FOREGROUND) {
            SDL_Window* window = SDL_GL_GetCurrentWindow();
            logf("  GL context %p current on window %p (%s)",
                 static_cast<void*>(SDL_GL_GetCurrentContext()), static_cast<void*>(window),
                 SDL_GetError());
        }
    }
    if ((event->type == SDL_EVENT_KEY_DOWN || event->type == SDL_EVENT_KEY_UP)
        && event->key.key == SDLK_AC_BACK) {
        event->key.key      = SDLK_ESCAPE;
        event->key.scancode = SDL_SCANCODE_ESCAPE;
    }
    return true;
}

float android_min_ui_scale(float framebuffer_scale)
{
    if (SDL_IsTV()) {
        return 0.0f;
    }
    // Android's density: 1.0 = 160 dpi, phones are typically 2.6-3.5.
    float density = SDL_GetDisplayContentScale(SDL_GetPrimaryDisplay());
    if (!(density > 0.0f)) {
        density = 1.0f;
    }
    const float dp_factor = SDL_IsTablet() ? 1.0f : 1.25f;
    const float fb        = framebuffer_scale > 0.0f ? framebuffer_scale : 1.0f;
    return density * dp_factor / fb;
}

std::string android_storage_root()
{
    if (const char* env = std::getenv("EXTERNAL_STORAGE"); env != nullptr && env[0] != '\0') {
        return env;
    }
    return "/storage/emulated/0";
}

bool android_open_folder_picker(const std::string& initial)
{
    {
        std::lock_guard<std::mutex> lock(g_pick_mutex);
        g_pick_result = AndroidPickResult::None;
        g_pick_path.clear();
    }
    auto*   env      = static_cast<JNIEnv*>(SDL_GetAndroidJNIEnv());
    auto    activity = static_cast<jobject>(SDL_GetAndroidActivity());
    if (env == nullptr || activity == nullptr) {
        return false;
    }
    bool    opened = false;
    jclass  cls    = env->GetObjectClass(activity);
    jmethodID method =
        cls != nullptr ? env->GetMethodID(cls, "openFolderPicker", "(Ljava/lang/String;)Z") : nullptr;
    if (method != nullptr) {
        jstring jinitial = env->NewStringUTF(initial.c_str());
        opened = env->CallBooleanMethod(activity, method, jinitial) == JNI_TRUE;
        env->DeleteLocalRef(jinitial);
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        opened = false;
    }
    if (cls != nullptr) {
        env->DeleteLocalRef(cls);
    }
    env->DeleteLocalRef(activity);
    logf("folder picker: %s", opened ? "system chooser opened" : "unavailable, using built-in");
    return opened;
}

AndroidPickResult android_take_picked_folder(std::string* path)
{
    std::lock_guard<std::mutex> lock(g_pick_mutex);
    const AndroidPickResult result = g_pick_result;
    if (result != AndroidPickResult::None) {
        if (path != nullptr) {
            *path = g_pick_path;
        }
        g_pick_result = AndroidPickResult::None;
        g_pick_path.clear();
    }
    return result;
}

}  // namespace sm2::osd

// Called by KeishoActivity when the system folder chooser returns.
// path: a filesystem path; "" when cancelled; null when no chooser could open.
extern "C" JNIEXPORT void JNICALL
Java_io_github_carlovacco8_keisho_KeishoActivity_nativeFolderPicked(JNIEnv* env, jclass,
                                                                     jstring path)
{
    using namespace sm2::osd;
    std::lock_guard<std::mutex> lock(g_pick_mutex);
    if (path == nullptr) {
        g_pick_result = AndroidPickResult::Unavailable;
        g_pick_path.clear();
        return;
    }
    const char* chars = env->GetStringUTFChars(path, nullptr);
    g_pick_path       = chars != nullptr ? chars : "";
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(path, chars);
    }
    g_pick_result = g_pick_path.empty() ? AndroidPickResult::Cancelled : AndroidPickResult::Picked;
}

// Called by KeishoActivity on life-cycle changes, for android.log.
extern "C" JNIEXPORT void JNICALL
Java_io_github_carlovacco8_keisho_KeishoActivity_nativeLog(JNIEnv* env, jclass, jstring text)
{
    if (text == nullptr) {
        return;
    }
    const char* chars = env->GetStringUTFChars(text, nullptr);
    if (chars != nullptr) {
        sm2::osd::logf("java: %s", chars);
        env->ReleaseStringUTFChars(text, chars);
    }
}

#endif  // __ANDROID__
