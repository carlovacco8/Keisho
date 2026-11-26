// Keisho — Android port glue.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Small helpers that adapt the desktop program to an Android SDL activity:
// working directory, HOME, and the TV remote's Back button.

#pragma once

#if defined(__ANDROID__)

#include <SDL3/SDL_events.h>

namespace sm2::osd {

/// Call first thing in main(). Points HOME and the working directory at the
/// app's external files directory (Android/data/<package>/files), where the
/// Java side has already copied games.xml, the translations and a default
/// keisho.ini. Everything Keisho writes (settings, saves, screenshots, the log)
/// therefore lands in a folder the user can reach from a PC.
void android_prepare();

/// Call on every polled event. Turns the remote's / pad's Back key into Esc,
/// which opens and closes Keisho's settings overlay.
void android_translate_event(SDL_Event* event);

}  // namespace sm2::osd

#endif  // __ANDROID__
