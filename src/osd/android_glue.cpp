// Keisho — Android port glue.
// SPDX-License-Identifier: GPL-3.0-or-later

#include "osd/android_glue.h"

#if defined(__ANDROID__)

#include <SDL3/SDL.h>
#include <SDL3/SDL_system.h>

#include "core/cpu_topology.h"
#include "core/log.h"

#include <android/log.h>
#include <dlfcn.h>
#include <sys/resource.h>
#include <jni.h>

#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <algorithm>
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
    // Controllers through Android's own input, never SDL's HIDAPI drivers.
    // HIDAPI claims some Bluetooth pads (the Amazon Luna one, for instance)
    // but cannot open them from an app, which left the pad dead: its buttons
    // reached nobody and Android turned B into Back.
    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI, "0");
    // A TV's remotes (the SHIELD remote, HDMI-CEC) are not players. As
    // joysticks they took both player slots before a real pad was found, so
    // the pad was never opened: its buttons went nowhere and B became Back.
    // As keys they still move through the list (arrows, Enter, Back).
    SDL_SetHint(SDL_HINT_TV_REMOTE_AS_JOYSTICK, "0");
    // Every real pad reaches the game through the app's own virtual pad
    // (KeishoActivity passes on its buttons and sticks), which works on every
    // device: SDL leaves the real ones alone, so none takes a player slot and
    // stays silent (the Amazon Luna pad on a SHIELD did).
    SDL_SetHint(SDL_HINT_GAMECONTROLLER_IGNORE_DEVICES_EXCEPT, "0x1209/0x7472");
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

namespace {

// APerformanceHint (Android 13+), looked up at run time so older Androids
// still start.
using HintGetManager    = void* (*)();
using HintCreateSession = void* (*)(void*, const int32_t*, size_t, int64_t);
using HintReport        = int (*)(void*, int64_t);

HintReport g_hint_report  = nullptr;
void*      g_hint_session = nullptr;

float g_emulation_ms = 0.0f;  // smoothed, emulation thread only
float g_frame_ms     = 0.0f;

// Frame skip: time owed against the machine's frame period, in ns.
constexpr s64 kFramePeriodNs = 17'384'000;  // 57.52 Hz
s64  g_debt_ns     = 0;
bool g_last_skipped = false;

void add_debt(u64 frame_ns)
{
    g_debt_ns += static_cast<s64>(frame_ns) - kFramePeriodNs;
    g_debt_ns = std::clamp<s64>(g_debt_ns, -kFramePeriodNs, kFramePeriodNs * 4);
}

void smooth(float& average, u64 nanoseconds)
{
    const float ms = static_cast<float>(nanoseconds) / 1.0e6f;
    average        = average <= 0.0f ? ms : average + (ms - average) * 0.05f;
}

}  // namespace

void android_boost_emulation_thread()
{
    // The fast cores only (big and prime on a phone), where there are slow ones.
    const sm2::core::CpuTopology topology = sm2::core::CpuTopology::detect();
    if (!topology.slow.empty() && !topology.fast.empty()) {
        sm2::core::pin_current_thread(topology.fast);
        SM2_INFO("android: emulation on %zu fast core(s), first cpu%d", topology.fast.size(),
                 topology.fast.front());
    }
    // A high priority: below the audio thread, above everything else of ours.
    const pid_t tid = gettid();
    if (setpriority(PRIO_PROCESS, static_cast<id_t>(tid), -10) == 0) {
        SM2_INFO("android: emulation thread priority raised");
    }
    // A performance-hint session: tells Android how long a frame may take, so
    // it raises the clocks before frames run late instead of after.
    void* lib = dlopen("libandroid.so", RTLD_NOW);
    if (lib == nullptr) {
        return;
    }
    auto get_manager    = reinterpret_cast<HintGetManager>(dlsym(lib, "APerformanceHint_getManager"));
    auto create_session = reinterpret_cast<HintCreateSession>(dlsym(lib, "APerformanceHint_createSession"));
    auto report         = reinterpret_cast<HintReport>(dlsym(lib, "APerformanceHint_reportActualWorkDuration"));
    if (get_manager == nullptr || create_session == nullptr || report == nullptr) {
        return;  // before Android 13
    }
    void* manager = get_manager();
    if (manager == nullptr) {
        return;
    }
    const int32_t tids[] = {static_cast<int32_t>(tid)};
    constexpr int64_t kFrameNs = 17'384'000;  // 57.52 Hz
    g_hint_session = create_session(manager, tids, 1, kFrameNs);
    if (g_hint_session != nullptr) {
        g_hint_report = report;
        SM2_INFO("android: performance hint session open");
    }
}

float android_emulation_ms() { return g_emulation_ms; }
float android_frame_ms() { return g_frame_ms; }

void android_emulation_time(u64 nanoseconds)
{
    smooth(g_emulation_ms, nanoseconds);
}

bool android_skip_frame(u64 elapsed_ns)
{
    // Never two in a row: at least every other frame is shown.
    if (g_last_skipped || g_debt_ns <= 0) {
        g_last_skipped = false;
        return false;
    }
    g_last_skipped = true;
    add_debt(elapsed_ns);
    return true;
}

void android_frame_work(u64 nanoseconds)
{
    smooth(g_frame_ms, nanoseconds);
    add_debt(nanoseconds);
    if (g_hint_session != nullptr && nanoseconds > 0) {
        g_hint_report(g_hint_session, static_cast<int64_t>(nanoseconds));
    }
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
    desc.name      = "M2 NEXUS pad";
    desc.vendor_id  = 0x1209;  // the one pad SDL is let see (see android_prepare)
    desc.product_id = 0x7472;
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

// ---------------------------------------------------------------------------
// Phone light gun: GunServer.java hands over each aim the phone sends.
// ---------------------------------------------------------------------------

namespace {
std::atomic<float>    g_rgun_x{0.5f};
std::atomic<float>    g_rgun_y{0.5f};
std::atomic<int>      g_rgun_buttons{0};
std::atomic<uint64_t> g_rgun_ms{0};
std::mutex            g_rgun_url_mutex;
std::string           g_rgun_url;
std::string           g_rgun_status;
}  // namespace

namespace sm2::osd {

bool android_remote_gun(float* x, float* y, bool* trigger, bool* reload)
{
    const uint64_t last = g_rgun_ms.load();
    // The page sends at least every 250 ms while open, so a longer silence
    // means the phone was put down or its page closed.
    if (last == 0 || SDL_GetTicks() - last > 1500) {
        return false;
    }
    const int b = g_rgun_buttons.load();
    *x       = g_rgun_x.load();
    *y       = g_rgun_y.load();
    *trigger = (b & 1) != 0;
    *reload  = (b & 2) != 0;
    return true;
}

std::string android_remote_gun_url()
{
    std::lock_guard<std::mutex> lock(g_rgun_url_mutex);
    return g_rgun_url;
}

std::string android_remote_gun_status()
{
    std::lock_guard<std::mutex> lock(g_rgun_url_mutex);
    return g_rgun_status;
}

}  // namespace sm2::osd

extern "C" JNIEXPORT void JNICALL
Java_io_github_carlovacco8_keisho_KeishoActivity_nativeRemoteGun(JNIEnv*, jclass, jfloat x, jfloat y,
                                                                 jint buttons)
{
    g_rgun_x.store(x);
    g_rgun_y.store(y);
    g_rgun_buttons.store(buttons);
    g_rgun_ms.store(SDL_GetTicks());
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_carlovacco8_keisho_KeishoActivity_nativeRemoteGunUrl(JNIEnv* env, jclass, jstring url)
{
    std::string text;
    if (url != nullptr) {
        const char* chars = env->GetStringUTFChars(url, nullptr);
        if (chars != nullptr) {
            text = chars;
            env->ReleaseStringUTFChars(url, chars);
        }
    }
    std::lock_guard<std::mutex> lock(g_rgun_url_mutex);
    g_rgun_url = std::move(text);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_carlovacco8_keisho_KeishoActivity_nativeRemoteGunStatus(JNIEnv* env, jclass, jstring text)
{
    std::string value;
    if (text != nullptr) {
        const char* chars = env->GetStringUTFChars(text, nullptr);
        if (chars != nullptr) {
            value = chars;
            env->ReleaseStringUTFChars(text, chars);
        }
    }
    std::lock_guard<std::mutex> lock(g_rgun_url_mutex);
    g_rgun_status = std::move(value);
}
