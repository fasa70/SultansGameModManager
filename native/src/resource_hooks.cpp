#include "modloader/resource_hooks.h"

#include "modloader/android_log.h"
#include "modloader/il2cpp_runtime.h"

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace modloader {
namespace {

using SpriteGetter = void* (*)(void*, void*, void*, const void*);
using AudioGetter = void* (*)(void*, void*, const void*);
using AudioSetClip = void (*)(void*, void*, const void*);
using AudioGetClip = void* (*)(void*, const void*);
using AudioPlay = void (*)(void*, const void*);
using PromiseCallback = void (*)(void*, void*, const void*);
using ApplicationUpdate = void (*)(void*, const void*);
using AudioIsPlaying = bool (*)(void*, const void*);

struct PendingAudio {
    std::string key;
    std::string path;
    GcHandle promise_handle;
    GcHandle path_handle;
};

struct ResourceState {
    const Il2CppApi* api = nullptr;
    std::unique_ptr<Il2CppRuntime> runtime;
    HookEngine* hooks = nullptr;
    ResourceOverrideIndex index;
    void* game_application_class = nullptr;
    void* sprite_loader = nullptr;
    void* audio_loader = nullptr;
    void* load_sprite_method = nullptr;
    void* load_audio_method = nullptr;
    void* get_name_method = nullptr;
    void* unity_object_alive_method = nullptr;
    void* unity_object_set_hide_flags_method = nullptr;
    void* get_clip_method = nullptr;
    void* set_clip_method = nullptr;
    void* play_method = nullptr;
    void* is_playing_method = nullptr;
    void* application_update_method = nullptr;
    void* card_sprite_trampoline = nullptr;
    void* head_sprite_trampoline = nullptr;
    void* mod_audio_trampoline = nullptr;
    void* set_clip_trampoline = nullptr;
    void* play_trampoline = nullptr;
    void* resolve_trampoline = nullptr;
    void* reject_trampoline = nullptr;
    void* application_update_trampoline = nullptr;
    std::unordered_map<std::string, void*> sprites;
    std::unordered_map<std::string, void*> audio_clips;
    std::unordered_map<std::string, bool> logged_image_requests;
    std::unordered_map<std::string, bool> logged_image_loads;
    std::unordered_map<void*, PendingAudio> pending;
    std::unordered_map<std::string, std::size_t> active_music_attempts;
    std::vector<GcHandle> retained;
    std::mutex mutex;
    std::mutex retained_mutex;
    std::recursive_mutex pending_mutex;
    std::mutex hook_mutex;
    std::atomic<bool> promise_hooks_ready{false};
    std::atomic<bool> audio_preloads_started{false};
};

ResourceState* g_state = nullptr;
thread_local bool g_replacing_audio = false;
thread_local PendingAudio* g_synchronous_pending = nullptr;
thread_local bool g_synchronous_resolved = false;

std::string ManagedString(void* object) {
    if (object == nullptr) {
        return {};
    }
    const auto* bytes = reinterpret_cast<const std::byte*>(object);
    const std::int32_t length = *reinterpret_cast<const std::int32_t*>(bytes + 0x10);
    const auto* text = reinterpret_cast<const char16_t*>(bytes + 0x14);
    if (length < 0) {
        return {};
    }
    std::string result;
    for (std::int32_t index = 0; index < length; ++index) {
        const std::uint32_t value = text[index];
        if (value <= 0x7fU) {
            result.push_back(static_cast<char>(value));
        } else if (value <= 0x7ffU) {
            result.push_back(static_cast<char>(0xc0U | (value >> 6U)));
            result.push_back(static_cast<char>(0x80U | (value & 0x3fU)));
        } else {
            result.push_back(static_cast<char>(0xe0U | (value >> 12U)));
            result.push_back(static_cast<char>(0x80U | ((value >> 6U) & 0x3fU)));
            result.push_back(static_cast<char>(0x80U | (value & 0x3fU)));
        }
    }
    return result;
}

std::string NormalizeKey(std::string value) {
    std::replace(value.begin(), value.end(), '\\', '/');
    return value;
}

bool Retain(ResourceState* state, void* object) {
    GcHandle handle = state->runtime->Retain(object, false);
    if (!handle.valid()) {
        return false;
    }
    std::lock_guard<std::mutex> lock(state->retained_mutex);
    state->retained.push_back(std::move(handle));
    return true;
}

void LogImageRequest(ResourceState* state, std::string_view kind, std::string_view raw,
                     std::string_view key, const ResourceOverride* override) {
    const std::string log_key = std::string(kind) + ":" + std::string(key);
    std::lock_guard<std::mutex> lock(state->mutex);
    if (!state->logged_image_requests.emplace(log_key, true).second) {
        return;
    }
    std::string message = "image_request kind=" + std::string(kind) +
        " raw=" + std::string(raw) + " key=" + std::string(key);
    if (override == nullptr) {
        message += " index=miss";
    } else {
        message += " index=hit mod=" + override->mod_name +
            " file=" + override->relative_path;
    }
    LogMessage(message.c_str());
}

void LogImageLoad(ResourceState* state, std::string_view key,
                  const ResourceOverride& override, std::string_view result) {
    const std::string log_key(key);
    std::lock_guard<std::mutex> lock(state->mutex);
    if (!state->logged_image_loads.emplace(log_key, true).second) {
        return;
    }
    const std::string message = "image_load key=" + log_key + " mod=" + override.mod_name +
        " file=" + override.relative_path + " result=" + std::string(result);
    LogMessage(message.c_str());
}

bool IsUnityObjectAlive(ResourceState* state, void* object) {
    if (object == nullptr || state->unity_object_alive_method == nullptr) {
        return object != nullptr;
    }
    void* parameters[] = {object};
    const auto result = state->runtime->Invoke(
        state->unity_object_alive_method, nullptr, parameters);
    const auto value = result.has_value() ? state->runtime->Unbox(*result) : std::nullopt;
    return value.has_value() && *reinterpret_cast<const bool*>(*value);
}

bool PreserveUnityObject(ResourceState* state, void* object) {
    if (object == nullptr || state->unity_object_set_hide_flags_method == nullptr) {
        return false;
    }
    constexpr std::int32_t dont_unload_unused_asset = 32;
    void* parameters[] = {const_cast<std::int32_t*>(&dont_unload_unused_asset)};
    return state->runtime->InvokeVoid(
        state->unity_object_set_hide_flags_method, object, parameters);
}

void* LoadSprite(std::string_view key) {
    ResourceState* state = g_state;
    if (state == nullptr) {
        return nullptr;
    }
    const std::string normalized(key);
    ResourceOverride override;
    {
        std::lock_guard<std::mutex> lock(state->mutex);
        const auto cached = state->sprites.find(normalized);
        if (cached != state->sprites.end()) {
            if (IsUnityObjectAlive(state, cached->second)) {
                return cached->second;
            }
            state->sprites.erase(cached);
            LogMessage(("image_cache key=" + normalized + " result=destroyed_reload").c_str());
        }
        const auto found = state->index.images.find(normalized);
        if (found == state->index.images.end()) {
            return nullptr;
        }
        override = found->second;
    }
    const auto managed_path = state->runtime->NewString(override.absolute_path);
    if (!managed_path.has_value()) {
        LogImageLoad(state, normalized, override, "new_string_failed");
        return nullptr;
    }
    GcHandle path_handle = state->runtime->Retain(*managed_path, false);
    void* parameters[] = {*managed_path};
    const auto sprite = path_handle.valid() ? state->runtime->Invoke(
        state->load_sprite_method, state->sprite_loader, parameters) : std::nullopt;
    if (!sprite.has_value()) {
        LogImageLoad(state, normalized, override, "load_null");
        return nullptr;
    }
    if (!PreserveUnityObject(state, *sprite) || !Retain(state, *sprite)) {
        LogImageLoad(state, normalized, override, "preserve_failed");
        return nullptr;
    }
    LogImageLoad(state, normalized, override, "loaded_preserved");
    std::lock_guard<std::mutex> lock(state->mutex);
    const auto [iterator, inserted] = state->sprites.emplace(normalized, *sprite);
    return iterator->second;
}

void* CardSpriteHook(void* instance, void* key, void* argument, const void* method) {
    void* original = reinterpret_cast<SpriteGetter>(g_state->card_sprite_trampoline)(instance, key, argument, method);
    const std::string raw = ManagedString(key);
    const std::string resource_key = NormalizeKey(raw);
    const auto found = g_state->index.images.find(resource_key);
    if (found != g_state->index.images.end()) {
        LogImageRequest(g_state, "card", raw, resource_key, &found->second);
    }
    void* replacement = LoadSprite(resource_key);
    return replacement == nullptr ? original : replacement;
}

void* HeadSpriteHook(void* instance, void* key, void* argument, const void* method) {
    const std::string raw = ManagedString(key);
    const std::string resource_key = "head/" + NormalizeKey(raw);
    const auto found = g_state->index.images.find(resource_key);
    LogImageRequest(g_state, "head", raw, resource_key,
                    found == g_state->index.images.end() ? nullptr : &found->second);
    if (found != g_state->index.images.end()) {
        if (void* replacement = LoadSprite(resource_key); replacement != nullptr) {
            return replacement;
        }
    }
    return reinterpret_cast<SpriteGetter>(g_state->head_sprite_trampoline)(
        instance, key, argument, method);
}

std::string ClipName(ResourceState* state, void* clip) {
    if (clip == nullptr) {
        return {};
    }
    const auto name = state->runtime->Invoke(state->get_name_method, clip, nullptr);
    return name.has_value() ? ManagedString(*name) : std::string();
}

bool StartAudioPreloads(ResourceState* state);

bool ReplaceActiveMusic(ResourceState* state, std::string_view key, void* replacement) {
    const auto sfx = state->runtime->StaticFieldValue(
        state->game_application_class, "sfx");
    const auto channels = sfx.has_value() ?
        state->runtime->InstanceFieldValue(*sfx, "musicChannels") : std::nullopt;
    const auto get_clip_code = state->runtime->MethodCode(state->get_clip_method);
    const auto is_playing_code = state->runtime->MethodCode(state->is_playing_method);
    if (!channels.has_value() || !get_clip_code.has_value() ||
        !is_playing_code.has_value()) {
        return false;
    }
    const auto* bytes = reinterpret_cast<const std::byte*>(*channels);
    const std::size_t length = *reinterpret_cast<const std::size_t*>(bytes + 0x18);
    if (length > 64) {
        return false;
    }
    auto* sources = reinterpret_cast<void* const*>(bytes + 0x20);
    for (std::size_t index = 0; index < length; ++index) {
        void* source = sources[index];
        if (source == nullptr) {
            continue;
        }
        void* current = reinterpret_cast<AudioGetClip>(*get_clip_code)(
            source, state->get_clip_method);
        if (current == replacement) {
            return true;
        }
        if (current == nullptr || ClipName(state, current) != key) {
            continue;
        }
        const bool was_playing = reinterpret_cast<AudioIsPlaying>(*is_playing_code)(
            source, state->is_playing_method);
        g_replacing_audio = true;
        reinterpret_cast<AudioSetClip>(state->set_clip_trampoline)(
            source, replacement, state->set_clip_method);
        g_replacing_audio = false;
        if (was_playing) {
            reinterpret_cast<AudioPlay>(state->play_trampoline)(
                source, state->play_method);
        }
        const std::string message = "audio=active_music_replaced key=" + std::string(key);
        LogMessage(message.c_str());
        return true;
    }
    return false;
}

void ApplicationUpdateHook(void* instance, const void* method) {
    ResourceState* state = g_state;
    reinterpret_cast<ApplicationUpdate>(state->application_update_trampoline)(
        instance, method);

    if (!state->audio_preloads_started.exchange(true, std::memory_order_acq_rel) &&
        !StartAudioPreloads(state)) {
        LogMessage("audio=preload_start_failed");
    }

    std::vector<std::pair<std::string, void*>> candidates;
    std::vector<std::string> expired;
    {
        std::lock_guard<std::mutex> lock(state->mutex);
        for (auto& [key, frames] : state->active_music_attempts) {
            ++frames;
            if (frames > 720) {
                expired.push_back(key);
                continue;
            }
            if (frames == 1 || frames % 60 == 0) {
                const auto clip = state->audio_clips.find(key);
                if (clip != state->audio_clips.end()) {
                    candidates.emplace_back(key, clip->second);
                }
            }
        }
        for (const std::string& key : expired) {
            state->active_music_attempts.erase(key);
        }
    }
    for (const auto& [key, clip] : candidates) {
        if (ReplaceActiveMusic(state, key, clip)) {
            std::lock_guard<std::mutex> lock(state->mutex);
            state->active_music_attempts.erase(key);
        }
    }
}

void PromiseResolveHook(void* promise, void* clip, const void* method) {
    ResourceState* state = g_state;
    PendingAudio pending;
    bool found = false;
    if (state != nullptr) {
        std::lock_guard<std::recursive_mutex> lock(state->pending_mutex);
        const auto iterator = state->pending.find(promise);
        if (iterator != state->pending.end()) {
            pending = std::move(iterator->second);
            state->pending.erase(iterator);
            found = true;
        } else if (g_synchronous_pending != nullptr) {
            pending.key = g_synchronous_pending->key;
            pending.path = g_synchronous_pending->path;
            g_synchronous_resolved = true;
            found = true;
        }
    }
    if (found && clip != nullptr && Retain(state, clip)) {
        std::lock_guard<std::mutex> lock(state->mutex);
        state->audio_clips[pending.key] = clip;
        state->active_music_attempts[pending.key] = 0;
    }
    if (state != nullptr && state->resolve_trampoline != nullptr) {
        reinterpret_cast<PromiseCallback>(state->resolve_trampoline)(promise, clip, method);
    }
}

void PromiseRejectHook(void* promise, void* exception, const void* method) {
    ResourceState* state = g_state;
    if (state != nullptr) {
        std::lock_guard<std::recursive_mutex> lock(state->pending_mutex);
        state->pending.erase(promise);
    }
    if (state != nullptr && state->reject_trampoline != nullptr) {
        reinterpret_cast<PromiseCallback>(state->reject_trampoline)(promise, exception, method);
    }
}

bool InstallPromiseHooks(ResourceState* state, void* promise) {
    if (state->promise_hooks_ready.load(std::memory_order_acquire)) {
        return true;
    }
    const auto klass = state->runtime->ObjectClass(promise);
    if (!klass.has_value()) {
        return false;
    }
    const auto resolve = state->runtime->FindMethod(*klass, "Resolve", 1);
    const auto reject = state->runtime->FindMethod(*klass, "Reject", 1);
    if (!resolve.has_value() || !reject.has_value()) {
        return false;
    }
    std::lock_guard<std::mutex> lock(state->hook_mutex);
    if (state->promise_hooks_ready.load(std::memory_order_acquire)) {
        return true;
    }
    if (!state->hooks->Replace(*state->runtime->MethodCode(*resolve),
                               reinterpret_cast<void*>(PromiseResolveHook),
                               &state->resolve_trampoline)) {
        return false;
    }
    if (!state->hooks->Replace(*state->runtime->MethodCode(*reject),
                               reinterpret_cast<void*>(PromiseRejectHook),
                               &state->reject_trampoline)) {
        return false;
    }
    state->promise_hooks_ready.store(true, std::memory_order_release);
    return true;
}

bool StartAudioPreloads(ResourceState* state) {
    auto install_promise_type = [&](std::string_view path) {
        const auto url = state->runtime->NewString("file://" + std::string(path));
        if (!url.has_value()) {
            return false;
        }
        GcHandle url_handle = state->runtime->Retain(*url, false);
        if (!url_handle.valid()) {
            return false;
        }
        void* parameters[] = {*url};
        const auto promise = state->runtime->Invoke(
            state->load_audio_method, state->audio_loader, parameters);
        return promise.has_value() && InstallPromiseHooks(state, *promise);
    };
    if (!state->index.audio.empty() &&
        !install_promise_type(state->index.audio.begin()->second.absolute_path)) {
        return false;
    }

    for (const auto& [key, override] : state->index.audio) {
        const auto url = state->runtime->NewString("file://" + override.absolute_path);
        if (!url.has_value()) {
            continue;
        }
        PendingAudio request;
        request.key = key;
        request.path = override.absolute_path;
        request.path_handle = state->runtime->Retain(*url, false);
        if (!request.path_handle.valid()) {
            continue;
        }
        void* parameters[] = {*url};
        std::lock_guard<std::recursive_mutex> lock(state->pending_mutex);
        g_synchronous_pending = &request;
        g_synchronous_resolved = false;
        const auto promise = state->runtime->Invoke(
            state->load_audio_method, state->audio_loader, parameters);
        g_synchronous_pending = nullptr;
        if (!promise.has_value() || g_synchronous_resolved) {
            continue;
        }
        request.promise_handle = state->runtime->Retain(*promise, false);
        if (!request.promise_handle.valid()) {
            continue;
        }
        state->pending.emplace(*promise, std::move(request));
    }
    return true;
}

void* ModAudioHook(void* instance, void* key, const void* method) {
    void* original = reinterpret_cast<AudioGetter>(g_state->mod_audio_trampoline)(instance, key, method);
    std::lock_guard<std::mutex> lock(g_state->mutex);
    const auto replacement = g_state->audio_clips.find(ManagedString(key));
    return replacement == g_state->audio_clips.end() ? original : replacement->second;
}

void SetClipHook(void* source, void* clip, const void* method) {
    ResourceState* state = g_state;
    void* replacement = clip;
    if (!g_replacing_audio && clip != nullptr) {
        const std::string key = ClipName(state, clip);
        std::lock_guard<std::mutex> lock(state->mutex);
        const auto found = state->audio_clips.find(key);
        if (found != state->audio_clips.end()) {
            replacement = found->second;
        }
    }
    reinterpret_cast<AudioSetClip>(state->set_clip_trampoline)(source, replacement, method);
}

void PlayHook(void* source, const void* method) {
    ResourceState* state = g_state;
    void* current = reinterpret_cast<AudioGetClip>(
        state->runtime->MethodCode(state->get_clip_method).value())(
            source, state->get_clip_method);
    if (current != nullptr) {
        const std::string key = ClipName(state, current);
        void* replacement = nullptr;
        {
            std::lock_guard<std::mutex> lock(state->mutex);
            const auto found = state->audio_clips.find(key);
            if (found != state->audio_clips.end() && found->second != current) {
                replacement = found->second;
            }
        }
        if (replacement != nullptr) {
            g_replacing_audio = true;
            reinterpret_cast<AudioSetClip>(state->set_clip_trampoline)(
                source, replacement, state->set_clip_method);
            g_replacing_audio = false;
        }
    }
    reinterpret_cast<AudioPlay>(state->play_trampoline)(source, method);
}

bool InstallImageHooks(ResourceState* state, void* core_image,
                       void* datapool_class, void* application_class) {
    if (state->index.images.empty()) {
        return true;
    }
    const auto unity_core = state->runtime->FindImage(
        {"UnityEngine.CoreModule.dll", "UnityEngine.CoreModule"});
    const auto unity_object = unity_core.has_value() ? state->runtime->FindClass(
        *unity_core, {"UnityEngine"}, "Object") : std::nullopt;
    const auto object_alive = unity_object.has_value() ? state->runtime->FindMethod(
        *unity_object, "op_Implicit", 1) : std::nullopt;
    const auto set_hide_flags = unity_object.has_value() ? state->runtime->FindMethod(
        *unity_object, "set_hideFlags", 1) : std::nullopt;
    const auto loader_class = state->runtime->FindClass(
        core_image, {"", "Il2Cpp"}, "SpriteLoader");
    const auto loader = state->runtime->StaticFieldValue(application_class, "spriteLoader");
    const auto load = loader_class.has_value() ?
        state->runtime->FindMethod(*loader_class, "LoadSpriteImmediate", 1) : std::nullopt;
    const auto card = state->runtime->FindMethod(datapool_class, "GetCardSprite", 2);
    const auto head = state->runtime->FindMethod(datapool_class, "GetHeadSprite", 2);
    if (!loader.has_value() || !load.has_value() || !card.has_value() ||
        !head.has_value() || !object_alive.has_value() ||
        !set_hide_flags.has_value()) {
        return false;
    }
    state->sprite_loader = *loader;
    state->load_sprite_method = *load;
    state->unity_object_alive_method = *object_alive;
    state->unity_object_set_hide_flags_method = *set_hide_flags;
    if (!state->hooks->Replace(*state->runtime->MethodCode(*card),
                               reinterpret_cast<void*>(CardSpriteHook),
                               &state->card_sprite_trampoline)) {
        return false;
    }
    if (!state->hooks->Replace(*state->runtime->MethodCode(*head),
                               reinterpret_cast<void*>(HeadSpriteHook),
                               &state->head_sprite_trampoline)) {
        return false;
    }
    return true;
}

bool InstallAudioHooks(ResourceState* state, void* core_image,
                       void* datapool_class, void* application_class,
                       std::size_t* preloads) {
    if (state->index.audio.empty()) {
        return true;
    }
    const auto loader_class = state->runtime->FindClass(
        core_image, {"", "Il2Cpp"}, "AudioClipLoader");
    const auto loader = state->runtime->StaticFieldValue(application_class, "audioClipLoader");
    const auto load = loader_class.has_value() ?
        state->runtime->FindMethod(*loader_class, "LoadAudioClip", 1) : std::nullopt;
    const auto mod_audio = state->runtime->FindMethod(datapool_class, "LoadModAudioClip", 1);
    const auto audio_image = state->runtime->FindImage(
        {"UnityEngine.AudioModule.dll", "UnityEngine.AudioModule"});
    const auto core_module = state->runtime->FindImage(
        {"UnityEngine.CoreModule.dll", "UnityEngine.CoreModule"});
    if (!loader.has_value() || !load.has_value() || !mod_audio.has_value() ||
        !audio_image.has_value() || !core_module.has_value()) {
        return false;
    }
    const auto source_class = state->runtime->FindClass(
        *audio_image, {"UnityEngine"}, "AudioSource");
    const auto object_class = state->runtime->FindClass(
        *core_module, {"UnityEngine"}, "Object");
    if (!source_class.has_value() || !object_class.has_value()) {
        return false;
    }
    const auto set_clip = state->runtime->FindMethod(*source_class, "set_clip", 1);
    const auto get_clip = state->runtime->FindMethod(*source_class, "get_clip", 0);
    const auto play = state->runtime->FindMethod(*source_class, "Play", 0);
    const auto is_playing = state->runtime->FindMethod(*source_class, "get_isPlaying", 0);
    const auto get_name = state->runtime->FindMethod(*object_class, "get_name", 0);
    const auto application_update = state->runtime->FindMethod(
        application_class, "Update", 0);
    if (!set_clip.has_value() || !get_clip.has_value() || !play.has_value() ||
        !is_playing.has_value() || !get_name.has_value() ||
        !application_update.has_value()) {
        return false;
    }
    state->audio_loader = *loader;
    state->load_audio_method = *load;
    state->set_clip_method = *set_clip;
    state->get_clip_method = *get_clip;
    state->play_method = *play;
    state->is_playing_method = *is_playing;
    state->application_update_method = *application_update;
    state->get_name_method = *get_name;

    if (!state->hooks->Replace(*state->runtime->MethodCode(*mod_audio),
                               reinterpret_cast<void*>(ModAudioHook),
                               &state->mod_audio_trampoline)) {
        return false;
    }
    if (!state->hooks->Replace(*state->runtime->MethodCode(*set_clip),
                               reinterpret_cast<void*>(SetClipHook),
                               &state->set_clip_trampoline)) {
        return false;
    }
    if (!state->hooks->Replace(*state->runtime->MethodCode(*play),
                               reinterpret_cast<void*>(PlayHook),
                               &state->play_trampoline)) {
        return false;
    }
    if (!state->hooks->Replace(*state->runtime->MethodCode(*application_update),
                               reinterpret_cast<void*>(ApplicationUpdateHook),
                               &state->application_update_trampoline)) {
        return false;
    }

    *preloads = state->index.audio.size();
    return true;
}

}  // namespace

ResourceHookStats InstallResourceHooks(const Il2CppApi& api,
                                       const ResourceOverrideIndex& resources,
                                       HookEngine* hooks) {
    ResourceHookStats stats;
    stats.image_paths = resources.images.size();
    stats.audio_paths = resources.audio.size();
    stats.image_collisions = resources.image_collisions.size();
    stats.rejected_resources = resources.rejected;
    if (hooks == nullptr || g_state != nullptr) {
        return stats;
    }
    Il2CppAttachedThread attached(api);
    if (!attached.attached()) {
        return stats;
    }
    auto state = std::make_unique<ResourceState>();
    state->api = &api;
    state->runtime = std::make_unique<Il2CppRuntime>(api);
    state->hooks = hooks;
    state->index = resources;
    const auto core = state->runtime->FindImage({"Core.dll", "Il2CppCore.dll"});
    if (!core.has_value()) {
        return stats;
    }
    const auto datapool = state->runtime->FindClass(*core, {"", "Il2Cpp"}, "Datapool");
    const auto application = state->runtime->FindClass(
        *core, {"", "Il2Cpp"}, "GameApplication");
    if (!datapool.has_value() || !application.has_value()) {
        return stats;
    }
    state->game_application_class = *application;
    g_state = state.release();
    stats.image_hooks_ready = InstallImageHooks(
        g_state, *core, *datapool, *application);
    stats.audio_hooks_ready = InstallAudioHooks(
        g_state, *core, *datapool, *application, &stats.audio_preloads);
    return stats;
}

}  // namespace modloader
