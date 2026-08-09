#include "modloader/tmp_glyph_hooks.h"

#include "modloader/android_log.h"
#include "modloader/game_profile.h"
#include "modloader/il2cpp_runtime.h"

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wvariadic-macros"
#include <dobby.h>
#pragma clang diagnostic pop

#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <utility>

namespace modloader {
namespace {

constexpr char kOldName[] = "m_SpriteGlyphTable";
constexpr char kNewName[] = "m_GlyphTable";
constexpr std::size_t kMaxStringLength = 256;

const Il2CppApi* g_api = nullptr;
GcHandle g_replacement;
void* g_replacement_string = nullptr;
std::mutex g_mutex;
std::size_t g_rewrites = 0;

bool IsManagedString(void* object, std::string* value) {
    if (object == nullptr || value == nullptr) {
        return false;
    }
    const auto* bytes = reinterpret_cast<const std::byte*>(object);
    const std::int32_t length = *reinterpret_cast<const std::int32_t*>(bytes + 0x10);
    if (length < 0 || static_cast<std::size_t>(length) > kMaxStringLength) {
        return false;
    }
    const auto* text = reinterpret_cast<const char16_t*>(bytes + 0x14);
    value->clear();
    value->reserve(static_cast<std::size_t>(length));
    for (std::int32_t index = 0; index < length; ++index) {
        const std::uint32_t value16 = text[index];
        if (value16 > 0x7fU) {
            return false;
        }
        value->push_back(static_cast<char>(value16));
    }
    return true;
}

void OnGlyphGetField(void*, void* raw_context) {
    auto* context = static_cast<DobbyRegisterContext*>(raw_context);
    if (context == nullptr || g_api == nullptr || g_replacement_string == nullptr) {
        return;
    }
    void* runtime_type = reinterpret_cast<void*>(context->general.regs.x0);
    void* field_name = reinterpret_cast<void*>(context->general.regs.x1);
    std::string actual;
    if (runtime_type == nullptr || !IsManagedString(field_name, &actual)) {
        return;
    }
    if (actual != kOldName) {
        return;
    }
    context->general.regs.x1 = reinterpret_cast<std::uintptr_t>(g_replacement_string);
    std::lock_guard<std::mutex> lock(g_mutex);
    ++g_rewrites;
    LogMessage("tmp_glyph_compat rewrite old=m_SpriteGlyphTable new=m_GlyphTable");
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
    std::lock_guard<std::mutex> lock(g_mutex);
    g_api = &api;
    g_replacement_string = replacement;
    g_replacement = std::move(handle);
    stats.ready = true;
    stats.fingerprint_matched = true;
    LogMessage("tmp_glyph_compat ready mode=callsite_instrument memory_only=true");
    return stats;
}

}  // namespace modloader
