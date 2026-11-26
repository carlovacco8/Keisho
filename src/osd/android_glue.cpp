// Keisho — Android port glue.
// SPDX-License-Identifier: GPL-3.0-or-later

#include "osd/android_glue.h"

#if defined(__ANDROID__)

#include <SDL3/SDL.h>
#include <SDL3/SDL_system.h>

#include <cstdlib>
#include <unistd.h>

namespace sm2::osd {

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
    }
}

void android_translate_event(SDL_Event* event)
{
    if (event == nullptr) {
        return;
    }
    if ((event->type == SDL_EVENT_KEY_DOWN || event->type == SDL_EVENT_KEY_UP)
        && event->key.key == SDLK_AC_BACK) {
        event->key.key      = SDLK_ESCAPE;
        event->key.scancode = SDL_SCANCODE_ESCAPE;
    }
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

}  // namespace sm2::osd

#endif  // __ANDROID__
