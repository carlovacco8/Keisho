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
#include <atomic>
#include <ctime>
#include <mutex>
#include <unistd.h>

namespace sm2::osd {

namespace {

std::FILE*  g_android_log = nullptr;
std::mutex  g_log_mutex;

std::atomic<int>  g_quick_slot{1};  ///< set by the SLOT button (nativeSetSlot)

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
    // Arcade monitors are landscape. Without this SDL lets a resizable window
    // follow the phone into portrait, overriding the manifest.
    SDL_SetHint(SDL_HINT_ORIENTATIONS, "LandscapeLeft LandscapeRight");

    // SDL picks the EGL config again whenever Android recreates the surface
    // (every return from another app), from the GL attributes current at that
    // moment. Keisho sets its attributes only when it creates the context,
    // after the window, so the first surface and the recreated one used
    // different configs, the context no longer matched and SDL replaced it:
    // a black screen. Setting the same attributes before the window exists
    // keeps the config identical across pauses.
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_ES);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 3);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 1);
    SDL_GL_SetAttribute(SDL_GL_DEPTH_SIZE, 0);
    SDL_GL_SetAttribute(SDL_GL_STENCIL_SIZE, 0);
    SDL_GL_SetAttribute(SDL_GL_DOUBLEBUFFER, 1);

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

void android_game_changed(const std::string& set)
{
    auto* env      = static_cast<JNIEnv*>(SDL_GetAndroidJNIEnv());
    auto  activity = static_cast<jobject>(SDL_GetAndroidActivity());
    if (env == nullptr || activity == nullptr) {
        return;
    }
    jclass    cls    = env->GetObjectClass(activity);
    jmethodID method = cls != nullptr ? env->GetMethodID(cls, "onGameChanged", "(Ljava/lang/String;)V") : nullptr;
    if (method != nullptr) {
        jstring jset = env->NewStringUTF(set.c_str());
        env->CallVoidMethod(activity, method, jset);
        env->DeleteLocalRef(jset);
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    if (cls != nullptr) {
        env->DeleteLocalRef(cls);
    }
    env->DeleteLocalRef(activity);
    logf("game: %s", set.empty() ? "(game list)" : set.c_str());
}

void android_menu_changed(bool open)
{
    auto* env      = static_cast<JNIEnv*>(SDL_GetAndroidJNIEnv());
    auto  activity = static_cast<jobject>(SDL_GetAndroidActivity());
    if (env == nullptr || activity == nullptr) {
        return;
    }
    jclass    cls    = env->GetObjectClass(activity);
    jmethodID method = cls != nullptr ? env->GetMethodID(cls, "onMenuChanged", "(Z)V") : nullptr;
    if (method != nullptr) {
        env->CallVoidMethod(activity, method, open ? JNI_TRUE : JNI_FALSE);
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    if (cls != nullptr) {
        env->DeleteLocalRef(cls);
    }
    env->DeleteLocalRef(activity);
}

std::string android_quick_slot()
{
    return std::to_string(g_quick_slot.load());
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

// ---------------------------------------------------------------------------
// On-screen touch controls: a virtual SDL gamepad driven by TouchOverlay.java.
// Keisho sees it as an ordinary pad (left stick, triggers, A/B/X/Y, shoulders,
// Start/Back), so every game's default pad layout applies unchanged.
// ---------------------------------------------------------------------------

namespace {
std::mutex     g_touch_mutex;
SDL_Joystick*  g_touch_pad = nullptr;
}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_carlovacco8_keisho_KeishoActivity_nativeTouchAttach(JNIEnv*, jclass)
{
    std::lock_guard<std::mutex> lock(g_touch_mutex);
    if (g_touch_pad != nullptr) {
        return JNI_TRUE;
    }
    if (!SDL_WasInit(SDL_INIT_JOYSTICK)) {
        return JNI_FALSE;  // Keisho has not opened its input yet; Java retries.
    }
    SDL_VirtualJoystickDesc desc;
    SDL_INIT_INTERFACE(&desc);
    desc.type     = SDL_JOYSTICK_TYPE_GAMEPAD;
    desc.naxes    = SDL_GAMEPAD_AXIS_COUNT;           // sticks + both triggers
    desc.nbuttons = SDL_GAMEPAD_BUTTON_DPAD_RIGHT + 1; // A..d-pad, in SDL order
    desc.name     = "Keisho touch controls";
    const SDL_JoystickID id = SDL_AttachVirtualJoystick(&desc);
    if (id == 0) {
        sm2::osd::logf("touch: could not attach the virtual pad: %s", SDL_GetError());
        return JNI_FALSE;
    }
    g_touch_pad = SDL_OpenJoystick(id);
    if (g_touch_pad == nullptr) {
        sm2::osd::logf("touch: could not open the virtual pad: %s", SDL_GetError());
        SDL_DetachVirtualJoystick(id);
        return JNI_FALSE;
    }
    // Triggers rest at the bottom of their range.
    SDL_SetJoystickVirtualAxis(g_touch_pad, SDL_GAMEPAD_AXIS_LEFT_TRIGGER, SDL_JOYSTICK_AXIS_MIN);
    SDL_SetJoystickVirtualAxis(g_touch_pad, SDL_GAMEPAD_AXIS_RIGHT_TRIGGER, SDL_JOYSTICK_AXIS_MIN);
    sm2::osd::logf("touch: virtual pad attached (id %u)", static_cast<unsigned>(id));
    return JNI_TRUE;
}

/// Removes the virtual pad, e.g. when a real controller is plugged in, so
/// the real one becomes player 1.
extern "C" JNIEXPORT void JNICALL
Java_io_github_carlovacco8_keisho_KeishoActivity_nativeTouchDetach(JNIEnv*, jclass)
{
    std::lock_guard<std::mutex> lock(g_touch_mutex);
    if (g_touch_pad == nullptr) {
        return;
    }
    const SDL_JoystickID id = SDL_GetJoystickID(g_touch_pad);
    SDL_CloseJoystick(g_touch_pad);
    g_touch_pad = nullptr;
    SDL_DetachVirtualJoystick(id);
    sm2::osd::logf("touch: virtual pad detached");
}

/// axis: SDL_GamepadAxis; value: -1..1 for sticks, 0..1 for triggers.
extern "C" JNIEXPORT void JNICALL
Java_io_github_carlovacco8_keisho_KeishoActivity_nativeTouchAxis(JNIEnv*, jclass, jint axis,
                                                                 jfloat value)
{
    std::lock_guard<std::mutex> lock(g_touch_mutex);
    if (g_touch_pad == nullptr || axis < 0 || axis >= SDL_GAMEPAD_AXIS_COUNT) {
        return;
    }
    float v = value;
    if (axis == SDL_GAMEPAD_AXIS_LEFT_TRIGGER || axis == SDL_GAMEPAD_AXIS_RIGHT_TRIGGER) {
        v = v * 2.0f - 1.0f;  // 0..1 -> full raw range, released = minimum
    }
    v = v < -1.0f ? -1.0f : (v > 1.0f ? 1.0f : v);
    const Sint16 raw = static_cast<Sint16>(v >= 0.0f ? v * SDL_JOYSTICK_AXIS_MAX
                                                     : -v * SDL_JOYSTICK_AXIS_MIN);
    SDL_SetJoystickVirtualAxis(g_touch_pad, axis, raw);
}

/// button: SDL_GamepadButton.
extern "C" JNIEXPORT void JNICALL
Java_io_github_carlovacco8_keisho_KeishoActivity_nativeTouchButton(JNIEnv*, jclass, jint button,
                                                                   jboolean down)
{
    std::lock_guard<std::mutex> lock(g_touch_mutex);
    if (g_touch_pad == nullptr || button < 0 || button > SDL_GAMEPAD_BUTTON_DPAD_RIGHT) {
        return;
    }
    SDL_SetJoystickVirtualButton(g_touch_pad, button, down == JNI_TRUE);
}

/// The SLOT button: which save-state slot the quick save / load keys use.
extern "C" JNIEXPORT void JNICALL
Java_io_github_carlovacco8_keisho_KeishoActivity_nativeSetSlot(JNIEnv*, jclass, jint slot)
{
    sm2::osd::g_quick_slot.store(slot < 1 ? 1 : (slot > 9 ? 9 : static_cast<int>(slot)));
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
