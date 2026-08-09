#include "modloader/official_resource_uri_hooks.h"

#include "modloader/android_log.h"
#include "modloader/il2cpp_runtime.h"
#include "modloader/resource_uri.h"

#include <atomic>
#include <cstddef>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <utility>
#include <vector>

namespace modloader {
namespace {

using StringLoader = void* (*)(void*, void*, const void*);
using StaticStringLoader = void* (*)(void*, const void*);

struct UriState {
    const Il2CppApi* api = nullptr;
    std::unique_ptr<Il2CppRuntime> runtime;
    std::string mod_root;
    void* sprite_original = nullptr;
    void* audio_original = nullptr;
    void* texture_original = nullptr;
    void* immediate_original = nullptr;
    std::atomic<std::size_t> rewrites{0};
};

UriState* g_state = nullptr;
std::mutex g_install_mutex;

void* RewriteArgument(void* path, ResourceArgumentMode mode, const char* loader) {
    UriState* state = g_state;
    if (state == nullptr) {
        return path;
    }
    const auto* bytes = reinterpret_cast<const std::byte*>(path);
    if (path == nullptr) {
        return path;
    }
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
    const auto argument = MakeOfficialResourceArgument(source, state->mod_root, mode);
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

void* SpriteHook(void* instance, void* path, const void* method) {
    UriState* state = g_state;
    const auto original = state == nullptr ? nullptr :
        reinterpret_cast<StringLoader>(state->sprite_original);
    return original == nullptr ? nullptr : original(
        instance, RewriteArgument(path, ResourceArgumentMode::kFileUri, "LoadSprite"), method);
}

void* AudioHook(void* instance, void* path, const void* method) {
    UriState* state = g_state;
    const auto original = state == nullptr ? nullptr :
        reinterpret_cast<StringLoader>(state->audio_original);
    return original == nullptr ? nullptr : original(
        instance, RewriteArgument(path, ResourceArgumentMode::kFileUri, "LoadAudioClip"), method);
}

void* ImmediateHook(void* instance, void* path, const void* method) {
    UriState* state = g_state;
    const auto original = state == nullptr ? nullptr :
        reinterpret_cast<StringLoader>(state->immediate_original);
    return original == nullptr ? nullptr : original(
        instance, RewriteArgument(path, ResourceArgumentMode::kAbsolutePath, "LoadSpriteImmediate"), method);
}

void* TextureHook(void* path, const void* method) {
    UriState* state = g_state;
    const auto original = state == nullptr ? nullptr :
        reinterpret_cast<StaticStringLoader>(state->texture_original);
    return original == nullptr ? nullptr : original(
        RewriteArgument(path, ResourceArgumentMode::kFileUri, "GetTexture"), method);
}

bool InstallOne(HookEngine* hooks, const Il2CppRuntime& runtime, void* method,
                void* replacement, void** original) {
    const auto code = runtime.MethodCode(method);
    return code.has_value() && hooks->Replace(*code, replacement, original) && *original != nullptr;
}

}  // namespace

OfficialResourceUriStats InstallOfficialResourceUriHooks(
    const Il2CppApi& api, std::string mod_root, HookEngine* hooks) {
    OfficialResourceUriStats stats;
    if (hooks == nullptr || mod_root.empty()) {
        return stats;
    }
    std::lock_guard<std::mutex> lock(g_install_mutex);
    if (g_state != nullptr) {
        stats.sprite_ready = g_state->sprite_original != nullptr;
        stats.audio_ready = g_state->audio_original != nullptr;
        stats.texture_ready = g_state->texture_original != nullptr;
        stats.rewrites = g_state->rewrites.load(std::memory_order_relaxed);
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
    const auto load_sprite = sprite.has_value() ? state->runtime->FindMethodByFirstParameter(
        *sprite, "LoadSprite", 1, "System.String") : std::nullopt;
    const auto load_immediate = sprite.has_value() ? state->runtime->FindMethodByFirstParameter(
        *sprite, "LoadSpriteImmediate", 1, "System.String") : std::nullopt;
    const auto load_audio = audio.has_value() ? state->runtime->FindMethodByFirstParameter(
        *audio, "LoadAudioClip", 1, "System.String") : std::nullopt;
    const auto texture_image = state->runtime->FindImage(
        {"UnityEngine.UnityWebRequestTextureModule.dll"});
    const auto texture_class = texture_image.has_value() ? state->runtime->FindClass(
        *texture_image, {"UnityEngine.Networking"}, "UnityWebRequestTexture") : std::nullopt;
    const auto get_texture = texture_class.has_value() ? state->runtime->FindMethodByFirstParameter(
        *texture_class, "GetTexture", 1, "System.String") : std::nullopt;

    if (!load_sprite.has_value() || !load_immediate.has_value() ||
        !load_audio.has_value() || !get_texture.has_value()) {
        LogMessage("official_uri unavailable reason=metadata");
        return stats;
    }
    if (!InstallOne(hooks, *state->runtime, *load_sprite,
                    reinterpret_cast<void*>(SpriteHook), &state->sprite_original) ||
        !InstallOne(hooks, *state->runtime, *load_immediate,
                    reinterpret_cast<void*>(ImmediateHook), &state->immediate_original) ||
        !InstallOne(hooks, *state->runtime, *load_audio,
                    reinterpret_cast<void*>(AudioHook), &state->audio_original) ||
        !InstallOne(hooks, *state->runtime, *get_texture,
                    reinterpret_cast<void*>(TextureHook), &state->texture_original)) {
        LogMessage("official_uri unavailable reason=hook_install");
        return stats;
    }
    g_state = state.release();
    stats.sprite_ready = true;
    stats.audio_ready = true;
    stats.texture_ready = true;
    LogMessage("official_uri ready modes=file_uri_and_immediate_absolute");
    return stats;
}

}  // namespace modloader
