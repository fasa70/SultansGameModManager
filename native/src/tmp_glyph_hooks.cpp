#include "modloader/tmp_glyph_hooks.h"

#include "modloader/android_log.h"
#include "modloader/game_profile.h"
#include "modloader/il2cpp_runtime.h"

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wvariadic-macros"
#include <dobby.h>
#pragma clang diagnostic pop

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <utility>

namespace modloader {
namespace {

constexpr char kOldName[] = "m_SpriteGlyphTable";
constexpr char kNewName[] = "m_GlyphTable";

std::atomic<bool> g_active{false};
const Il2CppApi* g_api = nullptr;
GcHandle g_replacement;
void* g_replacement_string = nullptr;
std::atomic<std::size_t> g_rewrites{0};

bool IsOldGlyphName(void* object) {
    if (object == nullptr) {
        return false;
    }
    const auto* bytes = reinterpret_cast<const std::byte*>(object);
    const std::int32_t length = *reinterpret_cast<const std::int32_t*>(bytes + 0x10);
    if (length != static_cast<std::int32_t>(sizeof(kOldName) - 1)) {
        return false;
    }
    const auto* text = reinterpret_cast<const char16_t*>(bytes + 0x14);
    for (std::size_t index = 0; index < sizeof(kOldName) - 1; ++index) {
        if (text[index] != static_cast<char16_t>(kOldName[index])) {
            return false;
        }
    }
    return true;
}

void OnGlyphGetField(void*, void* raw_context) {
    if (!g_active.load(std::memory_order_acquire)) {
        return;
    }
    auto* context = static_cast<DobbyRegisterContext*>(raw_context);
    if (context == nullptr || g_api == nullptr || g_replacement_string == nullptr) {
        return;
    }
    if (context->general.regs.x0 == 0 ||
        !IsOldGlyphName(reinterpret_cast<void*>(context->general.regs.x1))) {
        return;
    }
    context->general.regs.x1 = reinterpret_cast<std::uintptr_t>(g_replacement_string);
    g_rewrites.fetch_add(1, std::memory_order_relaxed);
}

}  // namespace

TmpGlyphHookStats InstallTmpGlyphHook(const Il2CppApi& api, HookEngine* hooks) {
    TmpGlyphHookStats stats;
    if (hooks == nullptr || api.image_base == nullptr || api.image_size == 0) {
        return stats;
    }
    const GameProfile& profile = SupportedGameProfile();
    const auto base = reinterpret_cast<std::uintptr_t>(api.image_base);
    if (!MatchesTmpGlyphFingerprint(profile, base, base, base + api.image_size)) {
        LogMessage("tmp_glyph_compat unavailable reason=fingerprint");
        return stats;
    }
    const auto replacement = api.string_new == nullptr
        ? nullptr : api.string_new(kNewName);
    if (replacement == nullptr || api.gchandle_new == nullptr) {
        LogMessage("tmp_glyph_compat unavailable reason=string_api");
        return stats;
    }
    GcHandle handle(api, replacement, true);
    if (!handle.valid()) {
        LogMessage("tmp_glyph_compat unavailable reason=gchandle");
        return stats;
    }
    const auto callsite = reinterpret_cast<void*>(base + profile.tmp_glyph.call_rva);
    if (!hooks->Instrument(callsite, OnGlyphGetField)) {
        LogMessage("tmp_glyph_compat unavailable reason=hook_install");
        return stats;
    }
    g_api = &api;
    g_replacement_string = replacement;
    g_replacement = std::move(handle);
    stats.ready = true;
    stats.fingerprint_matched = true;
    LogMessage("tmp_glyph_compat ready mode=callsite_instrument memory_only=true");
    return stats;
}

void SetTmpGlyphHookActive(bool active) noexcept {
    g_active.store(active, std::memory_order_release);
}

}  // namespace modloader
