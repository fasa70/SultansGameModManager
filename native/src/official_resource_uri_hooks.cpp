#include "modloader/official_resource_uri_hooks.h"

#include "modloader/android_log.h"
#include "modloader/game_profile.h"
#include "modloader/il2cpp_runtime.h"
#include "modloader/resource_uri.h"

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wvariadic-macros"
#include <dobby.h>
#pragma clang diagnostic pop

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <utility>
#include <vector>

namespace modloader {
namespace {

struct UriTarget {
    void* code = nullptr;
    ResourceArgumentMode mode = ResourceArgumentMode::kFileUri;
    std::uint8_t argument_index = 0;
    const char* label = nullptr;
};

struct UriState {
    const Il2CppApi* api = nullptr;
    std::unique_ptr<Il2CppRuntime> runtime;
    std::string mod_root;
    std::vector<UriTarget> targets;
    std::atomic<std::size_t> rewrites{0};
};

std::atomic<UriState*> g_state{nullptr};
std::atomic<bool> g_active{false};
std::mutex g_install_mutex;

bool MatchesCode(const Il2CppApi& api, const CodeFingerprint& fingerprint,
                 void* code) {
    if (api.image_base == nullptr || code == nullptr ||
        fingerprint.rva > api.image_size ||
        fingerprint.bytes.size() > api.image_size - fingerprint.rva) {
        return false;
    }
    const auto* expected = reinterpret_cast<const std::uint8_t*>(api.image_base) +
        fingerprint.rva;
    return code == expected && std::equal(
        fingerprint.bytes.begin(), fingerprint.bytes.end(), expected);
}

void* RewriteArgument(UriState* state, void* path, ResourceArgumentMode mode,
                      const char* loader) {
    if (state == nullptr || path == nullptr) {
        return path;
    }
    const auto* bytes = reinterpret_cast<const std::byte*>(path);
    const std::int32_t length = *reinterpret_cast<const std::int32_t*>(bytes + 0x10);
    if (length < 0 || length > 16 * 1024) {
        return path;
    }
    const auto* text = reinterpret_cast<const char16_t*>(bytes + 0x14);
    std::string source;
    source.reserve(static_cast<std::size_t>(length));
    for (std::int32_t index = 0; index < length; ++index) {
        if (text[index] > 0x7fU) {
            return path;
        }
        source.push_back(static_cast<char>(text[index]));
    }
    const auto argument = MakeOfficialResourceArgument(
        source, state->mod_root, mode);
    if (!argument.has_value()) {
        return path;
    }
    if (*argument == source) {
        LogMessage((std::string("official_uri pass loader=") + loader +
                    " reason=filesystem_absolute_path").c_str());
        return path;
    }
    const auto managed = state->runtime->NewString(*argument);
    if (!managed.has_value()) {
        LogMessage((std::string("official_uri unavailable loader=") + loader +
                    " reason=string_allocation").c_str());
        return path;
    }
    state->rewrites.fetch_add(1, std::memory_order_relaxed);
    LogMessage((std::string("official_uri rewrite loader=") + loader).c_str());
    return *managed;
}

void OnUriEntry(void* address, void* raw_context) {
    UriState* state = g_state.load(std::memory_order_acquire);
    auto* context = static_cast<DobbyRegisterContext*>(raw_context);
    if (state == nullptr || context == nullptr ||
        !g_active.load(std::memory_order_acquire)) {
        return;
    }
    const auto target = std::find_if(
        state->targets.begin(), state->targets.end(),
        [address](const UriTarget& candidate) {
            return candidate.code == address;
        });
    if (target == state->targets.end()) {
        return;
    }
    std::uintptr_t* argument = target->argument_index == 0
        ? &context->general.regs.x0
        : &context->general.regs.x1;
    *argument = reinterpret_cast<std::uintptr_t>(RewriteArgument(
        state, reinterpret_cast<void*>(*argument), target->mode,
        target->label));
}

std::optional<UriTarget> ResolveTarget(
    const Il2CppApi& api, const Il2CppRuntime& runtime, void* klass,
    std::string_view method_name,
    bool is_static, const CodeFingerprint& fingerprint,
    ResourceArgumentMode mode, std::uint8_t argument_index,
    const char* label) {
    const auto method = runtime.FindUniqueMethod(
        klass, method_name, {"System.String"}, is_static);
    if (!method.has_value()) {
        return std::nullopt;
    }
    const auto code = runtime.MethodCode(*method);
    if (!code.has_value() || !MatchesCode(
            api, fingerprint, *code)) {
        return std::nullopt;
    }
    return UriTarget{*code, mode, argument_index, label};
}

}  // namespace

OfficialResourceUriStats InstallOfficialResourceUriHooks(
    const Il2CppApi& api, std::string mod_root, HookEngine* hooks) {
    OfficialResourceUriStats stats;
    if (hooks == nullptr || mod_root.empty() || api.method_get_flags == nullptr) {
        LogMessage("official_uri unavailable reason=api");
        return stats;
    }
    std::lock_guard<std::mutex> lock(g_install_mutex);
    if (UriState* installed = g_state.load(std::memory_order_acquire);
        installed != nullptr) {
        stats.sprite_ready = true;
        stats.audio_ready = true;
        stats.texture_ready = true;
        stats.rewrites = installed->rewrites.load(std::memory_order_relaxed);
        return stats;
    }

    Il2CppAttachedThread attached(api);
    if (!attached.attached()) {
        LogMessage("official_uri unavailable reason=thread");
        return stats;
    }
    auto state = std::make_unique<UriState>();
    state->api = &api;
    state->runtime = std::make_unique<Il2CppRuntime>(api);
    state->mod_root = std::move(mod_root);
    const auto core = state->runtime->FindImage({"Core.dll", "Il2CppCore.dll"});
    const auto sprite = core.has_value() ? state->runtime->FindClass(
        *core, {"", "Il2Cpp"}, "SpriteLoader") : std::nullopt;
    const auto audio = core.has_value() ? state->runtime->FindClass(
        *core, {"", "Il2Cpp"}, "AudioClipLoader") : std::nullopt;
    const auto texture_image = state->runtime->FindImage(
        {"UnityEngine.UnityWebRequestTextureModule.dll"});
    const auto texture = texture_image.has_value() ? state->runtime->FindClass(
        *texture_image, {"UnityEngine.Networking"}, "UnityWebRequestTexture") :
        std::nullopt;
    if (!sprite.has_value() || !audio.has_value() || !texture.has_value()) {
        LogMessage("official_uri unavailable reason=metadata");
        return stats;
    }

    const OfficialResourceUriProfile& profile = SupportedGameProfile().resource_uri;
    const auto load_sprite = ResolveTarget(
        api, *state->runtime, *sprite, "LoadSprite", false, profile.load_sprite,
        ResourceArgumentMode::kFileUri, 1, "LoadSprite");
    const auto load_immediate = ResolveTarget(
        api, *state->runtime, *sprite, "LoadSpriteImmediate", false,
        profile.load_sprite_immediate, ResourceArgumentMode::kAbsolutePath, 1,
        "LoadSpriteImmediate");
    const auto load_audio = ResolveTarget(
        api, *state->runtime, *audio, "LoadAudioClip", false,
        profile.load_audio_clip, ResourceArgumentMode::kFileUri, 1,
        "LoadAudioClip");
    const auto get_texture = ResolveTarget(
        api, *state->runtime, *texture, "GetTexture", true, profile.get_texture,
        ResourceArgumentMode::kFileUri, 0, "GetTexture");
    if (!load_sprite.has_value() || !load_immediate.has_value() ||
        !load_audio.has_value() || !get_texture.has_value()) {
        LogMessage("official_uri unavailable reason=target_validation");
        return stats;
    }
    state->targets = {
        *load_sprite, *load_immediate, *load_audio, *get_texture,
    };

    for (const UriTarget& target : state->targets) {
        if (!hooks->Instrument(target.code, OnUriEntry)) {
            LogMessage("official_uri unavailable reason=hook_install");
            return stats;
        }
    }
    UriState* published = state.release();
    g_state.store(published, std::memory_order_release);
    stats.sprite_ready = true;
    stats.audio_ready = true;
    stats.texture_ready = true;
    LogMessage("official_uri ready mode=entry_instrument "
               "modes=file_uri_and_immediate_absolute direct_mod_calls=none");
    return stats;
}

void SetOfficialResourceUriHooksActive(bool active) noexcept {
    g_active.store(active, std::memory_order_release);
}

}  // namespace modloader
