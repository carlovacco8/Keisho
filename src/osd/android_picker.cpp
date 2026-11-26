// Keisho — Android port: the game list as M2EA draws it.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// On Android the game list replaces Keisho's desktop grid with a carousel of
// covers in M2EA's manner:
//
//   * a bar at the top: TRI-ARCADE and the version, the number of games, and the
//     tabs Games / Video / Input / Audio / System (the last four open
//     Keisho's own settings at that page);
//   * the covers in the middle, the chosen one large with an orange frame,
//     its neighbours smaller and dimmed; a finger drags them sideways, a tap
//     picks one, a tap on the chosen one plays it; dots under it;
//   * a bar at the foot: PLAY, SAVES (plays and opens the save states),
//     FIND (the ROM folders), the search field and QUIT.
//
// The D-pad or arrows move the choice, A or Enter plays.

#if defined(__ANDROID__)

#include "osd/gui.h"

#include "core/config.h"
#include "core/types.h"
#include "osd/scraper.h"

#include <imgui.h>
#include <imgui_internal.h>

#include <stb_image.h>

#include <SDL3/SDL.h>

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <initializer_list>
#include <string>
#include <vector>

namespace sm2::osd {

namespace {

// Keisho's settings pages, as numbered in gui.cpp's Page enum (a
// static_assert there keeps the two in step).
constexpr int kPageDisplay = 0;
constexpr int kPageAudio   = 1;
constexpr int kPageInput   = 2;
constexpr int kPagePaths   = 3;
constexpr int kPageStates  = 5;
constexpr int kPageSystem  = 7;

// M2EA's colours.
constexpr ImU32 kBackdrop  = IM_COL32(0x12, 0x14, 0x18, 0xFF);
constexpr ImU32 kBar       = IM_COL32(0x1A, 0x1D, 0x23, 0xF0);
constexpr ImU32 kBarEdge   = IM_COL32(0x2C, 0x30, 0x38, 0xFF);
constexpr ImU32 kButton    = IM_COL32(0x26, 0x2A, 0x31, 0xFF);
constexpr ImU32 kButtonHot = IM_COL32(0x34, 0x39, 0x42, 0xFF);
constexpr ImU32 kButtonEdge = IM_COL32(0x3A, 0x3F, 0x48, 0xFF);
constexpr ImU32 kOrange    = IM_COL32(0xE8, 0xA3, 0x3A, 0xFF);
constexpr ImU32 kOrangeHot = IM_COL32(0xF2, 0xB5, 0x55, 0xFF);
constexpr ImU32 kRed       = IM_COL32(0xC8, 0x47, 0x3C, 0xFF);
constexpr ImU32 kRedHot    = IM_COL32(0xD8, 0x58, 0x4C, 0xFF);
constexpr ImU32 kWhite     = IM_COL32(0xFF, 0xFF, 0xFF, 0xFF);
constexpr ImU32 kInk       = IM_COL32(0x12, 0x14, 0x18, 0xFF);
constexpr ImU32 kGrey      = IM_COL32(0xB4, 0xB8, 0xC0, 0xFF);
constexpr ImU32 kDim       = IM_COL32(0x80, 0x85, 0x8E, 0xFF);
constexpr ImU32 kCard      = IM_COL32(0x0E, 0x10, 0x14, 0xFF);

ImU32 alpha(ImU32 c, float a)
{
    const float have = static_cast<float>((c >> IM_COL32_A_SHIFT) & 0xFF);
    const ImU32 na   = static_cast<ImU32>(std::clamp(have * a, 0.0f, 255.0f) + 0.5f);
    return (c & ~IM_COL32_A_MASK) | (na << IM_COL32_A_SHIFT);
}

/// A colour for a game without a cover, the same every time for that game.
ImU32 placeholder_colour(const std::string& name)
{
    static constexpr ImU32 kHues[] = {
        IM_COL32(0x3B, 0x2A, 0x8C, 0xFF),  // violet
        IM_COL32(0x23, 0x86, 0x4E, 0xFF),  // green
        IM_COL32(0x1F, 0x5F, 0x9E, 0xFF),  // blue
        IM_COL32(0x9A, 0x2E, 0x34, 0xFF),  // red
        IM_COL32(0x1C, 0x7C, 0x80, 0xFF),  // teal
        IM_COL32(0xA0, 0x5A, 0x1C, 0xFF),  // amber
    };
    unsigned h = 2166136261u;
    for (unsigned char c : name) {
        h = (h ^ c) * 16777619u;
    }
    return kHues[h % (sizeof kHues / sizeof kHues[0])];
}

std::string upper(std::string s)
{
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return static_cast<char>(std::toupper(c)); });
    return s;
}

std::string lower(std::string s)
{
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return s;
}

/// Text centred in a box.
void text_centred(ImDrawList* list, float size, ImVec2 a, ImVec2 b, ImU32 colour, const char* text)
{
    const ImVec2 t = ImGui::GetFont()->CalcTextSizeA(size, FLT_MAX, 0.0f, text);
    list->AddText(ImGui::GetFont(), size, ImVec2(std::floor((a.x + b.x - t.x) * 0.5f), std::floor((a.y + b.y - t.y) * 0.5f)),
                  colour, text);
}

/// A rounded button drawn by hand; true when tapped.
bool pill_button(ImDrawList* list, const char* id, ImVec2 a, ImVec2 b, ImU32 fill, ImU32 fill_hot, ImU32 edge,
                 ImU32 ink, float text_size, const char* label, bool play_icon = false)
{
    ImGui::SetCursorScreenPos(a);
    const bool clicked = ImGui::InvisibleButton(id, ImVec2(b.x - a.x, b.y - a.y));
    const bool hot     = ImGui::IsItemActive();
    const float r      = (b.y - a.y) * 0.5f;
    list->AddRectFilled(a, b, hot ? fill_hot : fill, r);
    if (edge != 0) {
        list->AddRect(a, b, edge, r, 0, std::max(1.0f, (b.y - a.y) * 0.025f));
    }
    if (ImGui::IsItemFocused() && ImGui::GetIO().NavVisible) {
        list->AddRect(ImVec2(a.x - 3, a.y - 3), ImVec2(b.x + 3, b.y + 3), kWhite, r + 3, 0, 2.0f);
    }
    if (play_icon) {
        // ▶ then the label, the pair centred, as on M2EA's PLAY.
        const float  h  = (b.y - a.y) * 0.42f;
        const ImVec2 t  = ImGui::GetFont()->CalcTextSizeA(text_size, FLT_MAX, 0.0f, label);
        const float  gap = h * 0.9f;
        const float  w   = h * 0.85f + gap + t.x;
        const float  x0  = std::floor((a.x + b.x - w) * 0.5f);
        const float  cy  = (a.y + b.y) * 0.5f;
        list->AddTriangleFilled(ImVec2(x0, cy - h * 0.5f), ImVec2(x0, cy + h * 0.5f), ImVec2(x0 + h * 0.85f, cy), ink);
        list->AddText(ImGui::GetFont(), text_size, ImVec2(x0 + h * 0.85f + gap, std::floor(cy - t.y * 0.5f)), ink, label);
    } else {
        text_centred(list, text_size, a, b, ink, label);
    }
    return clicked;
}

}  // namespace

namespace {

/// A centred dialog in M2EA's colours: a title and big stacked buttons. The
/// index of the button pressed, or -1. Pad: up / down and A; B is `cancel`.
int big_dialog(const char* id, const char* title, std::initializer_list<const char*> buttons, int cancel)
{
    const ImGuiViewport* vp = ImGui::GetMainViewport();
    const float          H  = vp->WorkSize.y;
    const float          u  = std::max(0.5f, std::min(H / 591.0f, vp->WorkSize.x / 1100.0f));
    const ImGuiStyle&    st = ImGui::GetStyle();
    const float          fs = std::round(26.0f * u);
    const float          bw = std::round(300.0f * u);
    const float          bh = std::round(60.0f * u);

    if (!ImGui::IsPopupOpen(id)) {
        ImGui::OpenPopup(id);
    }
    ImGui::SetNextWindowPos(ImVec2(vp->WorkPos.x + vp->WorkSize.x * 0.5f, vp->WorkPos.y + H * 0.5f), ImGuiCond_Always,
                            ImVec2(0.5f, 0.5f));
    ImGui::PushStyleColor(ImGuiCol_PopupBg, ImGui::ColorConvertU32ToFloat4(IM_COL32(0x1A, 0x1D, 0x23, 0xF6)));
    ImGui::PushStyleColor(ImGuiCol_Border, ImGui::ColorConvertU32ToFloat4(kBarEdge));
    ImGui::PushStyleColor(ImGuiCol_ModalWindowDimBg, ImVec4(0, 0, 0, 0.55f));
    ImGui::PushStyleVar(ImGuiStyleVar_WindowRounding, std::round(18.0f * u));
    ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding, ImVec2(std::round(28.0f * u), std::round(24.0f * u)));
    ImGui::PushStyleVar(ImGuiStyleVar_ItemSpacing, ImVec2(0.0f, std::round(14.0f * u)));
    ImGui::PushStyleVar(ImGuiStyleVar_FrameRounding, bh * 0.5f);
    ImGui::PushStyleVar(ImGuiStyleVar_WindowBorderSize, std::max(1.0f, u));
    int pressed = -1;
    if (ImGui::BeginPopupModal(id, nullptr,
                               ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_AlwaysAutoResize
                                   | ImGuiWindowFlags_NoSavedSettings | ImGuiWindowFlags_NoMove)) {
        ImGui::PushFont(nullptr, fs / std::max(0.01f, st.FontScaleMain * st.FontScaleDpi));
        const float tw = ImGui::CalcTextSize(title).x;
        ImGui::SetCursorPosX(ImGui::GetCursorPosX() + std::max(0.0f, (bw - tw) * 0.5f));
        ImGui::TextColored(ImGui::ColorConvertU32ToFloat4(kOrange), "%s", title);
        ImGui::Dummy(ImVec2(bw, std::round(4.0f * u)));
        // The lit button is the orange one: the one the pad is on (or the
        // finger touched), remembered from the last frame.
        ImGuiStorage* store   = ImGui::GetStateStorage();
        const ImGuiID lit_key = ImGui::GetID("##lit");
        const int     lit     = ImGui::IsWindowAppearing() ? 0 : store->GetInt(lit_key, 0);
        int           lit_now = lit;
        int i = 0;
        for (const char* label : buttons) {
            const bool first = i == lit;
            ImGui::PushStyleColor(ImGuiCol_Button, ImGui::ColorConvertU32ToFloat4(first ? kOrange : kButton));
            ImGui::PushStyleColor(ImGuiCol_ButtonHovered, ImGui::ColorConvertU32ToFloat4(first ? kOrangeHot : kButtonHot));
            ImGui::PushStyleColor(ImGuiCol_ButtonActive, ImGui::ColorConvertU32ToFloat4(first ? kOrangeHot : kButtonHot));
            ImGui::PushStyleColor(ImGuiCol_Text, ImGui::ColorConvertU32ToFloat4(first ? kInk : kWhite));
            if (ImGui::Button(label, ImVec2(bw, bh))) {
                pressed = i;
            }
            if (ImGui::IsItemFocused() || ImGui::IsItemActive()) {
                lit_now = i;
            }
            if (i == 0 && ImGui::IsWindowAppearing()) {
                ImGui::SetItemDefaultFocus();
                ImGui::SetKeyboardFocusHere(-1);
            }
            ImGui::PopStyleColor(4);
            ++i;
        }
        store->SetInt(lit_key, lit_now);
        ImGui::PopFont();
        if (pressed < 0 && !ImGui::IsWindowAppearing()
            && (ImGui::IsKeyPressed(ImGuiKey_GamepadFaceRight, false) || ImGui::IsKeyPressed(ImGuiKey_Escape, false))) {
            pressed = cancel;
        }
        if (pressed >= 0) {
            ImGui::CloseCurrentPopup();
        }
        ImGui::EndPopup();
    }
    ImGui::PopStyleVar(5);
    ImGui::PopStyleColor(3);
    return pressed;
}

}  // namespace

void Gui::android_frame_hooks()
{
    // SELECT + START in a game: Resume / Exit Game. SELECT over the game list:
    // leave the app (Yes / No).
    const bool back_down  = ImGui::IsKeyDown(ImGuiKey_GamepadBack);
    const bool start_down = ImGui::IsKeyDown(ImGuiKey_GamepadStart);
    const bool combo      = (ImGui::IsKeyPressed(ImGuiKey_GamepadBack, false) && start_down)
                       || (ImGui::IsKeyPressed(ImGuiKey_GamepadStart, false) && back_down);
    const bool in_game = !m_picker_enabled && !m_current_game.empty();
    if (in_game && !m_visible && combo && !m_android_pause) {
        m_android_pause = true;
    } else if (m_android_pause) {
        const int pick = !in_game ? 0 : big_dialog("##pause", "PAUSE", {"RESUME", "EXIT GAME"}, 0);
        if (pick == 0) {
            m_android_pause = false;
        } else if (pick == 1) {
            m_android_pause     = false;
            m_android_exit_game = true;
        }
    }
    if (m_picker_enabled && !m_visible && !m_android_quit_prompt && !m_picker_searching
        && ImGui::IsKeyPressed(ImGuiKey_GamepadBack, false)) {
        m_android_quit_prompt = true;
    } else if (m_android_quit_prompt) {
        const int pick = big_dialog("##quit", "EXIT TRI-ARCADE?", {"NO", "YES"}, 0);
        if (pick == 0) {
            m_android_quit_prompt = false;
        } else if (pick == 1) {
            m_android_quit_prompt = false;
            SDL_Event quit{};
            quit.type = SDL_EVENT_QUIT;
            SDL_PushEvent(&quit);
        }
    }

    // SAVES: the game is running now, open its save states.
    if (m_android_states_after_launch && !m_picker_enabled && !m_current_game.empty()) {
        m_android_states_after_launch = false;
        m_page                        = kPageStates;
        m_visible                     = true;
    } else if (m_android_states_after_launch && m_picker_enabled && !m_pending_launch.has_value()
               && m_load_error.has_value()) {
        m_android_states_after_launch = false;  // the game did not start
    }
}

void Gui::draw_picker_android(Config& config)
{
    (void)config;
    poll_scraper_and_load_metadata();

    if (!SDL_CursorVisible()) {
        SDL_ShowCursor();
    }

    const ImGuiViewport* vp = ImGui::GetMainViewport();
    ImGui::SetNextWindowPos(vp->WorkPos);
    ImGui::SetNextWindowSize(vp->WorkSize);
    constexpr ImGuiWindowFlags flags = ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_NoMove
                                     | ImGuiWindowFlags_NoSavedSettings | ImGuiWindowFlags_NoBringToFrontOnFocus
                                     | ImGuiWindowFlags_NoScrollWithMouse | ImGuiWindowFlags_NoScrollbar
                                     | ImGuiWindowFlags_NoNav;
    ImGui::PushStyleVar(ImGuiStyleVar_WindowRounding, 0.0f);
    ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding, ImVec2(0.0f, 0.0f));
    ImGui::PushStyleVar(ImGuiStyleVar_WindowBorderSize, 0.0f);
    ImGui::PushStyleColor(ImGuiCol_WindowBg, ImGui::ColorConvertU32ToFloat4(kBackdrop));
    const bool open = ImGui::Begin("##picker_android", nullptr, flags);
    ImGui::PopStyleColor();
    ImGui::PopStyleVar(3);
    if (!open) {
        ImGui::End();
        return;
    }

    ImDrawList*    list = ImGui::GetWindowDrawList();
    const ImGuiIO& io   = ImGui::GetIO();
    const ImVec2   o    = vp->WorkPos;
    const float    W    = vp->WorkSize.x;
    const float    H    = vp->WorkSize.y;
    // Design units: M2EA's layout measured on a 1280 x 591 landscape phone.
    const float u  = std::max(0.5f, std::min(H / 591.0f, W / 1100.0f));
    const float dt = io.DeltaTime > 0.0f ? io.DeltaTime : 1.0f / 60.0f;

    // Two large faint circles behind everything, as on M2EA.
    list->AddCircleFilled(ImVec2(o.x + W * 0.13f, o.y + H * 1.02f), H * 0.62f, IM_COL32(255, 255, 255, 6), 96);
    list->AddCircleFilled(ImVec2(o.x + W * 0.93f, o.y + H * 0.12f), H * 0.55f, IM_COL32(255, 255, 255, 5), 96);

    // -- the games shown, by the search --------------------------------------
    const std::string needle = lower(m_picker_search);
    std::vector<int>  shown;
    for (int i = 0; i < static_cast<int>(m_picker_entries.size()); ++i) {
        const PickerEntry& e = m_picker_entries[static_cast<size_t>(i)];
        if (!needle.empty() && lower(e.title + " " + e.name).find(needle) == std::string::npos) {
            continue;
        }
        shown.push_back(i);
    }
    int sel = -1;
    for (size_t i = 0; i < shown.size(); ++i) {
        if (shown[i] == m_picker_selected) sel = static_cast<int>(i);
    }
    if (sel < 0 && !shown.empty()) {
        sel               = 0;
        m_picker_selected = shown[0];
        m_car_pos         = 0.0f;
    }
    if (static_cast<int>(shown.size()) != m_car_last_count) {
        // The list changed (a search, a rescan): jump, do not slide.
        m_car_last_count = static_cast<int>(shown.size());
        m_car_pos        = static_cast<float>(std::max(sel, 0));
        m_car_pressing   = false;
        m_car_dragging   = false;
    }
    const auto choose = [&](int i) {
        if (i < 0 || i >= static_cast<int>(shown.size())) return;
        sel               = i;
        m_picker_selected = shown[static_cast<size_t>(i)];
    };
    const auto play = [&]() {
        if (sel >= 0 && !m_load_error.has_value()) {
            m_pending_launch = m_picker_entries[static_cast<size_t>(m_picker_selected)].name;
        }
    };

    // A pad, the remote or the arrows: up and down move between the tabs, the
    // covers and the buttons; left and right move inside a row; A / Enter
    // presses what is lit.
    const auto key = [](std::initializer_list<ImGuiKey> keys) {
        for (ImGuiKey k : keys) {
            if (ImGui::IsKeyPressed(k, true)) return true;
        }
        return false;
    };
    const bool typing = io.WantTextInput;
    bool nav_left = false, nav_right = false, nav_ok = false;
    if (!typing && !m_load_error.has_value() && !m_android_quit_prompt) {
        nav_left  = key({ImGuiKey_LeftArrow, ImGuiKey_GamepadDpadLeft, ImGuiKey_GamepadLStickLeft});
        nav_right = key({ImGuiKey_RightArrow, ImGuiKey_GamepadDpadRight, ImGuiKey_GamepadLStickRight});
        nav_ok    = ImGui::IsKeyPressed(ImGuiKey_Enter, false) || ImGui::IsKeyPressed(ImGuiKey_KeypadEnter, false)
                 || ImGui::IsKeyPressed(ImGuiKey_GamepadFaceDown, false);
        if (key({ImGuiKey_UpArrow, ImGuiKey_GamepadDpadUp, ImGuiKey_GamepadLStickUp})) {
            m_car_zone = std::max(0, m_car_zone - 1);
        }
        if (key({ImGuiKey_DownArrow, ImGuiKey_GamepadDpadDown, ImGuiKey_GamepadLStickDown})) {
            m_car_zone = std::min(2, m_car_zone + 1);
        }
        if (m_car_zone == 0) {
            if (nav_left) m_car_tab = std::max(0, m_car_tab - 1);
            if (nav_right) m_car_tab = std::min(4, m_car_tab + 1);
        } else if (m_car_zone == 2) {
            if (nav_left) m_car_button = std::max(0, m_car_button - 1);
            if (nav_right) m_car_button = std::min(4, m_car_button + 1);
        }
    }
    if (ImGui::IsMouseClicked(ImGuiMouseButton_Left)) {
        m_car_zone = 1;  // a finger: no lit button
    }
    const bool lit_zone_ok = nav_ok && m_car_zone != 1;
    const float ring = std::max(2.0f, std::round(3.0f * u));
    const auto lit = [&](ImVec2 a, ImVec2 b, float r) {
        list->AddRect(ImVec2(a.x - ring * 1.5f, a.y - ring * 1.5f), ImVec2(b.x + ring * 1.5f, b.y + ring * 1.5f), kWhite,
                      r + ring * 1.5f, 0, ring);
    };

    const float margin = std::round(12.0f * u);

    // -- the top bar ------------------------------------------------------------
    const ImVec2 top_a(o.x + margin, o.y + std::round(14.0f * u));
    const ImVec2 top_b(o.x + W - margin, top_a.y + std::round(66.0f * u));
    const float  top_h = top_b.y - top_a.y;
    list->AddRectFilled(top_a, top_b, kBar, top_h * 0.32f);
    list->AddRect(top_a, top_b, kBarEdge, top_h * 0.32f, 0, std::max(1.0f, u));
    {
        const float  big   = std::round(25.0f * u);
        const float  small = std::round(12.0f * u);
        const ImVec2 at(top_a.x + std::round(22.0f * u), top_a.y + std::round(8.0f * u));
        list->AddText(ImGui::GetFont(), big, at, kOrange, "TRI-ARCADE");
        const float kw = ImGui::GetFont()->CalcTextSizeA(big, FLT_MAX, 0.0f, "TRI-ARCADE").x;
        const std::string ver = std::string("v") + SM2_VERSION;
        list->AddText(ImGui::GetFont(), small, ImVec2(at.x + kw + std::round(10.0f * u), at.y + big - small - 2.0f * u),
                      kDim, ver.c_str());
        char count[64];
        if (needle.empty()) {
            std::snprintf(count, sizeof count, "%zu GAMES", m_picker_entries.size());
        } else {
            std::snprintf(count, sizeof count, "%zu OF %zu GAMES", shown.size(), m_picker_entries.size());
        }
        list->AddText(ImGui::GetFont(), small, ImVec2(at.x, at.y + big + std::round(5.0f * u)), kDim, count);
    }
    {
        // The tabs, centred. Games is this page; the others open the settings.
        struct Tab {
            const char* label;
            int         page;  // -1: the game list
        };
        static constexpr Tab kTabs[] = {
            {"Games", -1}, {"Video", kPageDisplay}, {"Input", kPageInput}, {"Audio", kPageAudio}, {"System", kPageSystem},
        };
        const float size = std::round(27.0f * u);
        const float padx = std::round(15.0f * u);
        const float gap  = std::round(4.0f * u);
        float       total = 0.0f;
        for (const Tab& t : kTabs) {
            total += ImGui::GetFont()->CalcTextSizeA(size, FLT_MAX, 0.0f, t.label).x + padx * 2.0f + gap;
        }
        total -= gap;
        float       x = std::floor(o.x + (W - total) * 0.5f);
        const float h = top_h - std::round(10.0f * u);
        const float y = top_a.y + std::round(5.0f * u);
        int tab_index = 0;
        for (const Tab& t : kTabs) {
            const bool   is_lit = m_car_zone == 0 && m_car_tab == tab_index++;
            const float  w = ImGui::GetFont()->CalcTextSizeA(size, FLT_MAX, 0.0f, t.label).x + padx * 2.0f;
            const ImVec2 a(x, y), b(x + w, y + h);
            ImGui::PushID(t.label);
            ImGui::SetCursorScreenPos(a);
            const bool tapped = ImGui::InvisibleButton("##tab", ImVec2(w, h)) || (is_lit && lit_zone_ok);
            const bool held   = ImGui::IsItemActive();
            if (is_lit) lit(a, b, h * 0.5f);
            ImGui::PopID();
            if (t.page < 0) {
                list->AddRectFilled(a, b, kWhite, h * 0.5f);
                text_centred(list, size, a, b, kInk, t.label);
            } else {
                if (held) list->AddRectFilled(a, b, kButton, h * 0.5f);
                text_centred(list, size, a, b, held ? kWhite : kGrey, t.label);
                if (tapped) {
                    m_page    = t.page;
                    m_visible = true;
                }
            }
            x += w + gap;
        }
    }

    // -- the bottom bar -----------------------------------------------------------
    const ImVec2 bot_b(o.x + W - margin, o.y + H - std::round(12.0f * u));
    const ImVec2 bot_a(o.x + margin, bot_b.y - std::round(83.0f * u));
    const float  bot_h = bot_b.y - bot_a.y;
    list->AddRectFilled(bot_a, bot_b, kBar, bot_h * 0.3f);
    list->AddRect(bot_a, bot_b, kBarEdge, bot_h * 0.3f, 0, std::max(1.0f, u));
    {
        const float pad   = std::round(16.0f * u);
        const float bh    = std::round(62.0f * u);
        const float y0    = std::floor(bot_a.y + (bot_h - bh) * 0.5f);
        const float y1    = y0 + bh;
        const float text  = std::round(21.0f * u);
        const float gap   = std::round(16.0f * u);
        const float inner = (bot_b.x - pad) - (bot_a.x + pad);
        // Widths as on M2EA, shrunk together on narrower screens.
        float w_play = 248.0f * u, w_saves = 198.0f * u, w_find = 198.0f * u, w_quit = 143.0f * u;
        float w_search_min = 220.0f * u;
        const float need = w_play + w_saves + w_find + w_search_min + w_quit + gap * 4.0f + 20.0f * u;
        if (need > inner) {
            const float k = inner / need;
            w_play *= k;
            w_saves *= k;
            w_find *= k;
            w_quit *= k;
            w_search_min *= k;
        }
        float x = bot_a.x + pad;
        const auto lit_button = [&](int i, ImVec2 a, ImVec2 b) {
            const bool on = m_car_zone == 2 && m_car_button == i;
            if (on) lit(a, b, (b.y - a.y) * 0.5f);
            return on && lit_zone_ok;
        };
        if (pill_button(list, "##play", ImVec2(x, y0), ImVec2(x + w_play, y1), kOrange, kOrangeHot, 0, kInk, text,
                        "PLAY", true)
            | lit_button(0, ImVec2(x, y0), ImVec2(x + w_play, y1))) {
            play();
        }
        x += w_play + gap;
        if (pill_button(list, "##saves", ImVec2(x, y0), ImVec2(x + w_saves, y1), kButton, kButtonHot, kButtonEdge,
                        kWhite, text, "SAVES")
            | lit_button(1, ImVec2(x, y0), ImVec2(x + w_saves, y1))) {
            if (sel >= 0 && !m_load_error.has_value()) {
                play();
                m_android_states_after_launch = true;
            }
        }
        x += w_saves + gap;
        if (pill_button(list, "##find", ImVec2(x, y0), ImVec2(x + w_find, y1), kButton, kButtonHot, kButtonEdge, kWhite,
                        text, "FIND")
            | lit_button(2, ImVec2(x, y0), ImVec2(x + w_find, y1))) {
            m_page    = kPagePaths;
            m_visible = true;
        }
        x += w_find + gap;

        const float  quit_x = bot_b.x - pad - w_quit;
        const ImVec2 sa(x, y0 + std::round(2.0f * u));
        const ImVec2 sb(std::max(x + w_search_min, quit_x - gap * 4.0f), y1 - std::round(2.0f * u));
        {
            // The search: a white field, its hint centred.
            const float r = (sb.y - sa.y) * 0.5f;
            list->AddRectFilled(sa, sb, kWhite, r);
            ImGui::SetCursorScreenPos(ImVec2(sa.x + r, sa.y));
            ImGui::SetNextItemWidth(sb.x - sa.x - r * 2.0f);
            const float fs = std::round(19.0f * u);
            // PushFont's size is scaled by the overlay's own font scale; undo it.
            const ImGuiStyle& st = ImGui::GetStyle();
            ImGui::PushFont(nullptr, fs / std::max(0.01f, st.FontScaleMain * st.FontScaleDpi));
            ImGui::PushStyleVar(ImGuiStyleVar_FramePadding, ImVec2(0.0f, std::floor((sb.y - sa.y - fs) * 0.5f)));
            ImGui::PushStyleVar(ImGuiStyleVar_FrameBorderSize, 0.0f);
            ImGui::PushStyleColor(ImGuiCol_FrameBg, ImVec4(0, 0, 0, 0));
            ImGui::PushStyleColor(ImGuiCol_FrameBgHovered, ImVec4(0, 0, 0, 0));
            ImGui::PushStyleColor(ImGuiCol_FrameBgActive, ImVec4(0, 0, 0, 0));
            ImGui::PushStyleColor(ImGuiCol_Text, ImGui::ColorConvertU32ToFloat4(kInk));
            if (m_picker_drop_search) {
                ImGui::ClearActiveID();
                m_picker_drop_search = false;
            }
            if (lit_button(3, sa, sb)) {
                ImGui::SetKeyboardFocusHere();  // the search, from the pad: start typing
            }
            const bool enter = ImGui::InputText("##search", m_picker_search, sizeof m_picker_search,
                                                ImGuiInputTextFlags_EnterReturnsTrue);
            const bool active = ImGui::IsItemActive();
            m_picker_searching = active;
            ImGui::PopStyleColor(4);
            ImGui::PopStyleVar(2);
            ImGui::PopFont();
            if (m_picker_search[0] == '\0' && !active) {
                text_centred(list, fs, sa, sb, kDim, "SEARCH");
            }
            if (m_picker_search[0] != '\0' && !active) {
                // A tap on the x at the right clears it.
                const ImVec2 c(sb.x - r, (sa.y + sb.y) * 0.5f);
                const float  k = r * 0.28f;
                list->AddLine(ImVec2(c.x - k, c.y - k), ImVec2(c.x + k, c.y + k), kDim, 2.0f * u);
                list->AddLine(ImVec2(c.x - k, c.y + k), ImVec2(c.x + k, c.y - k), kDim, 2.0f * u);
                if (ImGui::IsMouseClicked(ImGuiMouseButton_Left)
                    && ImGui::IsMouseHoveringRect(ImVec2(c.x - r, sa.y), ImVec2(sb.x, sb.y))) {
                    m_picker_search[0] = '\0';
                }
            }
            if (enter) {
                play();  // the first match
            }
        }
        if (pill_button(list, "##quit", ImVec2(quit_x, y0), ImVec2(quit_x + w_quit, y1), kRed, kRedHot, 0, kWhite, text,
                        "QUIT")
            | lit_button(4, ImVec2(quit_x, y0), ImVec2(quit_x + w_quit, y1))) {
            SDL_Event quit{};
            quit.type = SDL_EVENT_QUIT;
            SDL_PushEvent(&quit);
        }
    }

    // -- the carousel ---------------------------------------------------------------
    const float area_top = top_b.y + std::round(6.0f * u);
    const float area_bot = bot_a.y - std::round(6.0f * u);
    const float dots_h   = std::round(24.0f * u);
    const float card_h   = std::floor(area_bot - area_top - dots_h);
    const float card_w   = std::floor(card_h * 0.708f);
    const float step     = card_w * 1.19f;   // centre of the chosen card to its neighbour's
    const float far_step = card_w * 0.86f;   // and on to the next ones
    const float side     = 0.77f;            // a neighbour's size
    const ImVec2 centre(std::floor(o.x + W * 0.5f), std::floor(area_top + card_h * 0.5f));
    const int  n         = static_cast<int>(shown.size());

    if (m_picker_entries.empty() || n == 0) {
        const char* msg = m_picker_entries.empty()
                              ? "No games found. Tap FIND to choose the folder with your ROMs."
                              : "No game matches the search.";
        const float fs = std::round(22.0f * u);
        list->AddText(ImGui::GetFont(), fs, ImVec2(o.x + W * 0.5f - W * 0.3f, centre.y - fs), kGrey, msg, nullptr,
                      W * 0.6f);
        ImGui::End();
        return;
    }

    // The covers' row: left / right choose, A plays.
    if (m_car_zone == 1) {
        if (nav_right) choose(std::min(sel + 1, n - 1));
        if (nav_left) choose(std::max(sel - 1, 0));
        if (nav_ok) play();
    }

    // A finger on the covers: drag sideways to scroll, tap to choose or play.
    const ImVec2 area_a(o.x, area_top), area_b(o.x + W, area_bot);
    const auto   card_rect = [&](float d, ImVec2* a, ImVec2* b) {
        const float ad = std::fabs(d);
        const float s  = 1.0f - (1.0f - side) * std::min(ad, 1.0f);
        const float off = std::min(ad, 1.0f) * step + std::max(ad - 1.0f, 0.0f) * far_step;
        const float cx  = centre.x + (d < 0.0f ? -off : off);
        const float w = card_w * s, h = card_h * s;
        *a = ImVec2(std::floor(cx - w * 0.5f), std::floor(centre.y - h * 0.5f));
        *b = ImVec2(a->x + std::floor(w), a->y + std::floor(h));
    };
    const bool over_area = ImGui::IsMouseHoveringRect(area_a, area_b, false) && !ImGui::IsAnyItemHovered()
                         && ImGui::IsWindowHovered(ImGuiHoveredFlags_AllowWhenBlockedByActiveItem);
    if (ImGui::IsMouseClicked(ImGuiMouseButton_Left) && over_area && !m_load_error.has_value()) {
        m_car_pressing  = true;
        m_car_dragging  = false;
        m_car_press_x   = io.MousePos.x;
        m_car_press_pos = m_car_pos;
        m_car_velocity  = 0.0f;
        if (typing) ImGui::ClearActiveID();  // a tap outside the search ends it
    }
    if (m_car_pressing) {
        const float moved = io.MousePos.x - m_car_press_x;
        if (!m_car_dragging && std::fabs(moved) > 12.0f * u) {
            m_car_dragging = true;
        }
        if (m_car_dragging) {
            const float before = m_car_pos;
            m_car_pos = std::clamp(m_car_press_pos - moved / step, -0.4f, static_cast<float>(n - 1) + 0.4f);
            m_car_velocity = m_car_velocity * 0.6f + ((m_car_pos - before) / dt) * 0.4f;
        }
        if (!io.MouseDown[0]) {
            m_car_pressing = false;
            if (m_car_dragging) {
                // Let go: settle on the nearest cover, a flick carrying it on.
                const float flung = m_car_pos + std::clamp(m_car_velocity * 0.18f, -3.0f, 3.0f);
                choose(std::clamp(static_cast<int>(std::lround(flung)), 0, n - 1));
                m_car_dragging = false;
            } else {
                // A tap: on the chosen cover plays it, on another chooses it.
                int hit = -1;
                for (int i : {sel, sel - 1, sel + 1, sel - 2, sel + 2}) {
                    if (i < 0 || i >= n) continue;
                    ImVec2 a, b;
                    card_rect(static_cast<float>(i) - m_car_pos, &a, &b);
                    if (io.MousePos.x >= a.x && io.MousePos.x < b.x && io.MousePos.y >= a.y && io.MousePos.y < b.y) {
                        hit = i;
                        break;
                    }
                }
                if (hit == sel && std::fabs(m_car_pos - static_cast<float>(sel)) < 0.15f) {
                    play();
                } else if (hit >= 0) {
                    choose(hit);
                }
            }
        }
    }
    if (!m_car_dragging) {
        // Ease towards the chosen cover.
        const float target = static_cast<float>(sel);
        m_car_pos += (target - m_car_pos) * (1.0f - std::exp(-14.0f * dt));
        if (std::fabs(target - m_car_pos) < 0.001f) m_car_pos = target;
    }

    // The covers, farthest first so the chosen one is drawn on top.
    std::vector<int> order;
    for (int i = 0; i < n; ++i) {
        if (std::fabs(static_cast<float>(i) - m_car_pos) < 3.2f) order.push_back(i);
    }
    std::sort(order.begin(), order.end(), [&](int a, int b) {
        return std::fabs(static_cast<float>(a) - m_car_pos) > std::fabs(static_cast<float>(b) - m_car_pos);
    });
    list->PushClipRect(area_a, area_b, true);
    for (const int i : order) {
        PickerEntry& entry = m_picker_entries[static_cast<size_t>(shown[static_cast<size_t>(i)])];
        const float  d     = static_cast<float>(i) - m_car_pos;
        const float  near  = 1.0f - std::min(std::fabs(d), 1.0f);  // 1 for the chosen, 0 for the others
        ImVec2       a, b;
        card_rect(d, &a, &b);
        const float w = b.x - a.x, h = b.y - a.y;
        const float r = std::round(w * 0.04f);

        // The cover, loaded the first time it comes near.
        if (entry.art_state == PickerEntry::Art::Unknown && m_picker_backend != nullptr) {
            std::vector<std::string> images;
            if (!entry.cover.empty()) images.push_back(entry.cover);
            if (m_picker_scraper != nullptr) {
                const Scraper::Metadata meta = m_picker_scraper->load_metadata(entry.name, entry.title);
                images.insert(images.end(), meta.image_paths.begin(), meta.image_paths.end());
            }
            for (const std::string& path : images) {
                int            iw = 0, ih = 0, ch = 0;
                unsigned char* px = stbi_load(path.c_str(), &iw, &ih, &ch, 4);
                if (px == nullptr) continue;
                const render::Backend::TextureHandle handle =
                    iw > 0 && ih > 0 ? m_picker_backend->create_texture(static_cast<u32>(iw), static_cast<u32>(ih), px) : 0;
                stbi_image_free(px);
                if (handle != 0) {
                    entry.art.push_back({handle, static_cast<float>(iw), static_cast<float>(ih)});
                    break;  // one picture is all a card shows
                }
            }
            entry.art_state = entry.art.empty() ? PickerEntry::Art::None : PickerEntry::Art::Loaded;
        }

        const float  shadow = std::round(6.0f * u);
        list->AddRectFilled(ImVec2(a.x + shadow * 0.5f, a.y + shadow), ImVec2(b.x + shadow * 0.5f, b.y + shadow),
                            IM_COL32(0, 0, 0, 90), r);
        list->AddRectFilled(a, b, kCard, r);
        const float  inset  = std::round(7.0f * u * (b.x - a.x) / card_w);
        const float  foot_h = std::round(h * 0.145f);
        const ImVec2 art_a(a.x + inset, a.y + inset);
        const ImVec2 art_b(b.x - inset, b.y - foot_h);
        const float  shade = 0.45f + 0.55f * near;  // the others are dimmed
        if (entry.art_state == PickerEntry::Art::Loaded && !entry.art.empty()) {
            // Fill the picture box, cropping what overflows.
            const PickerEntry::Art1& art = entry.art.front();
            const float bw = art_b.x - art_a.x, bh = art_b.y - art_a.y;
            ImVec2      uv0(0, 0), uv1(1, 1);
            if (art.w > 0.0f && art.h > 0.0f) {
                const float box = bw / bh, img = art.w / art.h;
                if (img > box) {
                    const float keep = box / img;
                    uv0.x = (1.0f - keep) * 0.5f;
                    uv1.x = 1.0f - uv0.x;
                } else {
                    const float keep = img / box;
                    uv0.y = (1.0f - keep) * 0.5f;
                    uv1.y = 1.0f - uv0.y;
                }
            }
            const ImU8  g = static_cast<ImU8>(255.0f * shade);
            list->AddImageRounded(reinterpret_cast<ImTextureID>(m_picker_backend->texture_imgui_id(art.handle)), art_a,
                                  art_b, uv0, uv1, IM_COL32(g, g, g, 255), std::round(r * 0.5f),
                                  ImDrawFlags_RoundCornersTop);
        } else {
            // No cover: a colour fading to black, and the title on it.
            const ImU32 top = alpha(placeholder_colour(entry.name), shade);
            const ImU32 bot = IM_COL32(0x0B, 0x0C, 0x10, 0xFF);
            list->AddRectFilledMultiColor(art_a, art_b, top, top, bot, bot);
            const float fs = std::round(22.0f * u * w / card_w);
            list->PushClipRect(art_a, art_b, true);
            list->AddText(ImGui::GetFont(), fs, ImVec2(art_a.x + fs * 0.6f, art_a.y + fs * 0.6f),
                          alpha(kWhite, 0.25f + 0.35f * near), upper(entry.title).c_str(), nullptr,
                          art_b.x - art_a.x - fs * 1.2f);
            list->PopClipRect();
        }
        // The line over the caption, then the title and its details.
        list->AddLine(ImVec2(a.x + 1, art_b.y), ImVec2(b.x - 1, art_b.y), alpha(kWhite, 0.45f * shade),
                      std::max(1.0f, u));
        {
            const float scale = w / card_w;
            const float t1    = std::round(19.0f * u * scale);
            const float t2    = std::round(13.0f * u * scale);
            const float tx    = a.x + std::round(11.0f * u * scale);
            const float ty    = art_b.y + std::round(8.0f * u * scale);
            list->PushClipRect(ImVec2(a.x, art_b.y), ImVec2(b.x - std::round(8.0f * u), b.y), true);
            list->AddText(ImGui::GetFont(), t1, ImVec2(tx, ty), alpha(kWhite, near > 0.5f ? 1.0f : 0.5f),
                          upper(entry.title).c_str());
            std::string sub = entry.system;
            if (!entry.year.empty()) sub += (sub.empty() ? "" : " \xC2\xB7 ") + entry.year;
            if (!entry.manufacturer.empty()) sub = entry.manufacturer + (sub.empty() ? "" : " \xC2\xB7 ") + sub;
            list->AddText(ImGui::GetFont(), t2, ImVec2(tx, ty + t1 + std::round(4.0f * u * scale)),
                          near > 0.5f ? kOrange : alpha(kGrey, 0.5f), sub.c_str());
            list->PopClipRect();
        }
        // The frame: orange round the chosen one.
        if (near > 0.5f) {
            const float t = std::round(3.0f * u);
            list->AddRect(ImVec2(a.x - t * 0.5f, a.y - t * 0.5f), ImVec2(b.x + t * 0.5f, b.y + t * 0.5f),
                          alpha(kOrange, (near - 0.5f) * 2.0f), r + t * 0.5f, 0, t);
        } else {
            list->AddRect(a, b, alpha(kGrey, 0.55f), r, 0, std::max(1.0f, 1.5f * u));
        }
    }
    list->PopClipRect();

    // The dots under it (or a count, past a dozen games).
    {
        const float y = area_top + card_h + dots_h * 0.55f;
        if (n <= 12) {
            const float gap = std::round(23.0f * u);
            const float x0  = centre.x - gap * static_cast<float>(n - 1) * 0.5f;
            for (int i = 0; i < n; ++i) {
                list->AddCircleFilled(ImVec2(x0 + gap * static_cast<float>(i), y), std::round(5.5f * u),
                                      i == sel ? kOrange : IM_COL32(0x6B, 0x6F, 0x78, 0xFF));
            }
        } else {
            char at[32];
            std::snprintf(at, sizeof at, "%d / %d", sel + 1, n);
            text_centred(list, std::round(14.0f * u), ImVec2(centre.x - 100.0f * u, y - 10.0f * u),
                         ImVec2(centre.x + 100.0f * u, y + 10.0f * u), kDim, at);
        }
    }

    ImGui::End();
}

}  // namespace sm2::osd

#endif  // __ANDROID__
