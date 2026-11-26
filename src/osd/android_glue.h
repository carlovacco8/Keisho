// Keisho — Android port glue.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Small helpers that adapt the desktop program to an Android SDL activity:
// working directory, HOME, the TV remote's Back button, the system folder
// picker, overlay sizing and a lifecycle log for diagnosing the port.

#pragma once

#if defined(__ANDROID__)

#include "core/types.h"

#include <SDL3/SDL_events.h>

#include <string>

namespace sm2::osd {

/// Call first thing in main(). Points HOME and the working directory at the
/// app's external files directory (Android/data/<package>/files), where the
/// Java side has already copied games.xml, the translations and a default
/// keisho.ini. Everything Keisho writes (settings, saves, screenshots, the log)
/// therefore lands in a folder the user can reach from a PC. Also starts
/// android.log there, which records SDL's own messages and app life-cycle
/// events (pause, resume, surface and GL context changes).
void android_prepare();

/// Call on every polled event. Turns the remote's / pad's Back key into Esc,
/// which opens and closes Keisho's settings overlay, and logs life-cycle
/// events. Returns false when the event should be dropped.
bool android_translate_event(SDL_Event* event);

/// The smallest overlay scale that keeps text readable on this device, in
/// the overlay's own units (it multiplies a 13 px font measured in ImGui
/// display units; framebuffer_scale converts those to physical pixels).
/// Phones get text about 16 dp tall, tablets a little less; TVs return 0,
/// keeping Keisho's own fit-to-screen size.
[[nodiscard]] float android_min_ui_scale(float framebuffer_scale);

/// Internal shared storage ("/storage/emulated/0"), where a built-in folder
/// browser should start rather than in the app's private folder.
[[nodiscard]] std::string android_storage_root();

/// Opens Android's own folder chooser. Returns false when the device has none
/// (some TV boxes), in which case the caller shows its built-in browser.
[[nodiscard]] bool android_open_folder_picker(const std::string& initial);

/// Tells the Java side which game is running ("" = the game list is up), so
/// the on-screen controls match it and hide over the list. Call when it changes.
void android_game_changed(const std::string& set);

/// Call once on the emulation thread: runs it on the fast cores at a high
/// priority, and opens an Android performance-hint session for it (Android
/// 13+) so the system raises the clocks when a frame runs late.
void android_boost_emulation_thread();

/// Each frame, the time the emulation thread worked on it (before pacing),
/// for the performance-hint session.
void android_frame_work(u64 nanoseconds);

/// Model 3 frame skip: called after a frame is emulated, before it is drawn,
/// with the time spent on it so far. True to skip drawing it: when frames
/// have been taking longer than the machine's 17.4 ms, every other frame is
/// emulated but not drawn, so the game and its sound keep full speed.
[[nodiscard]] bool android_skip_frame(u64 elapsed_ns);

/// Each frame, the time the machine's run_frame() took (the emulation alone).
void android_emulation_time(u64 nanoseconds);

/// Smoothed milliseconds per frame, for STATS: the emulation alone, and the
/// whole frame's work (emulation, drawing, presenting).
[[nodiscard]] float android_emulation_ms();
[[nodiscard]] float android_frame_ms();

/// Tells the Java side whether Keisho's settings are open, so the on-screen
/// controls step aside while they are. Call when it changes.
void android_menu_changed(bool open);

/// The save-state slot the touch controls' SLOT button chose ("1" to "4"),
/// used by the quick save / load keys.
[[nodiscard]] std::string android_quick_slot();

enum class AndroidPickResult { None, Picked, Cancelled, Unavailable };

/// Polled each frame: the outcome of android_open_folder_picker(), once.
[[nodiscard]] AndroidPickResult android_take_picked_folder(std::string* path);

}  // namespace sm2::osd

#endif  // __ANDROID__
