#include "modloader/ui_reveal_hooks.h"

#include "modloader/android_log.h"
#include "modloader/il2cpp_runtime.h"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace modloader {
namespace {

using StartFunction = void (*)(void*, const void*);
using UnitySetActive = void (*)(void*, bool);
using BehaviourSetEnabled = void (*)(void*, bool);

constexpr std::string_view kTargetPath = "MainUI/StartPanel/Mod";
constexpr std::uintptr_t kStartRva = 0x1fd64b4;
constexpr std::uint8_t kStartBytes[] = {
    0xfe, 0x0f, 0x1b, 0xf8, 0xfa, 0x67, 0x01, 0xa9,
    0xf8, 0x5f, 0x02, 0xa9, 0xf6, 0x57, 0x03, 0xa9,
};

struct UiState {
    const Il2CppApi* api = nullptr;
    std::unique_ptr<Il2CppRuntime> runtime;
    void* start_original = nullptr;
    void* game_object_class = nullptr;
    void* platform_disable_class = nullptr;
    void* find_all_method = nullptr;
    void* get_name_method = nullptr;
    void* get_transform_method = nullptr;
    void* get_parent_method = nullptr;
    void* component_get_game_object_method = nullptr;
    UnitySetActive set_active = nullptr;
    BehaviourSetEnabled set_enabled = nullptr;
    std::int32_t mobile_offset = -1;
    std::int32_t switch_only_offset = -1;
    bool metadata_ready = false;
    bool revealed = false;
};

UiState* g_state = nullptr;
std::mutex g_mutex;

bool MatchCode(const Il2CppApi& api, std::uintptr_t rva,
               const std::uint8_t* bytes, std::size_t size) {
    if (api.image_base == nullptr || rva > api.image_size || size > api.image_size - rva) {
        return false;
    }
    const auto* actual = reinterpret_cast<const std::uint8_t*>(api.image_base) + rva;
    return std::equal(actual, actual + size, bytes);
}

std::string ManagedString(void* object) {
    if (object == nullptr) return {};
    const auto* bytes = reinterpret_cast<const std::byte*>(object);
    const std::int32_t length = *reinterpret_cast<const std::int32_t*>(bytes + 0x10);
    if (length < 0 || length > 256) return {};
    const auto* text = reinterpret_cast<const char16_t*>(bytes + 0x14);
    std::string result;
    result.reserve(static_cast<std::size_t>(length));
    for (std::int32_t index = 0; index < length; ++index) {
        if (text[index] > 0x7fU) return {};
        result.push_back(static_cast<char>(text[index]));
    }
    return result;
}

std::optional<void*> Invoke(UiState* state, void* method, void* object,
                            void** args = nullptr) {
    return state->runtime->Invoke(method, object, args);
}

bool ResolveMetadata(UiState* state) {
    if (state->metadata_ready) return true;
    const auto core = state->runtime->FindImage({"UnityEngine.CoreModule.dll", "UnityEngine.dll"});
    const auto app = state->runtime->FindImage({"Core.dll", "Il2CppCore.dll", "Assembly-CSharp.dll"});
    if (!core.has_value() || !app.has_value()) return false;
    const auto resources = state->runtime->FindClass(*core, {"UnityEngine"}, "Resources");
    const auto game_object = state->runtime->FindClass(*core, {"UnityEngine"}, "GameObject");
    const auto unity_object = state->runtime->FindClass(*core, {"UnityEngine"}, "Object");
    const auto transform = state->runtime->FindClass(*core, {"UnityEngine"}, "Transform");
    const auto component = state->runtime->FindClass(*core, {"UnityEngine"}, "Component");
    const auto platform_disable = state->runtime->FindClass(*app, {""}, "PlatformGODisable");
    if (!resources || !game_object || !unity_object || !transform || !component || !platform_disable) {
        return false;
    }
    const auto find_all = state->runtime->FindMethod(*resources, "FindObjectsOfTypeAll", 1);
    const auto get_name = state->runtime->FindMethod(*unity_object, "get_name", 0);
    const auto get_transform = state->runtime->FindMethod(*game_object, "get_transform", 0);
    const auto get_parent = state->runtime->FindMethod(*transform, "get_parent", 0);
    const auto get_game_object = state->runtime->FindMethod(*component, "get_gameObject", 0);
    const auto mobile = state->runtime->FieldOffset(*platform_disable, "Mobile");
    const auto switch_only = state->runtime->FieldOffset(*platform_disable, "SwitchOnly");
    if (!find_all || !get_name || !get_transform || !get_parent || !get_game_object ||
        !mobile || !switch_only) return false;
    state->game_object_class = *game_object;
    state->platform_disable_class = *platform_disable;
    state->find_all_method = *find_all;
    state->get_name_method = *get_name;
    state->get_transform_method = *get_transform;
    state->get_parent_method = *get_parent;
    state->component_get_game_object_method = *get_game_object;
    state->mobile_offset = *mobile;
    state->switch_only_offset = *switch_only;
    state->metadata_ready = true;
    return true;
}

std::optional<void*> ParentGameObject(UiState* state, void* object) {
    const auto transform = Invoke(state, state->get_transform_method, object);
    if (!transform || *transform == nullptr) return std::nullopt;
    const auto parent = Invoke(state, state->get_parent_method, *transform);
    if (!parent || *parent == nullptr) return std::nullopt;
    return Invoke(state, state->component_get_game_object_method, *parent);
}

bool HasHierarchy(UiState* state, void* object) {
    std::string path;
    for (int depth = 0; depth < 24 && object != nullptr; ++depth) {
        const auto name = Invoke(state, state->get_name_method, object);
        if (!name) return false;
        const std::string segment = ManagedString(*name);
        if (segment.empty()) return false;
        path = path.empty() ? segment : segment + "/" + path;
        const auto parent = ParentGameObject(state, object);
        if (!parent) break;
        object = *parent;
    }
    return path == kTargetPath;
}

std::vector<void*> FindAll(UiState* state, void* klass) {
    const void* type = state->api->class_get_type(klass);
    void* managed_type = type == nullptr ? nullptr : state->api->type_get_object(type);
    if (managed_type == nullptr) return {};
    void* args[] = {managed_type};
    const auto array = Invoke(state, state->find_all_method, nullptr, args);
    if (!array || *array == nullptr) return {};
    const std::size_t count = std::min<std::size_t>(state->api->array_length(*array), 8192);
    std::vector<void*> result;
    result.reserve(count);
    for (std::size_t index = 0; index < count; ++index) {
        void* object = *reinterpret_cast<void**>(reinterpret_cast<std::byte*>(*array) +
            0x20 + index * sizeof(void*));
        if (object != nullptr) result.push_back(object);
    }
    return result;
}

void RevealOnUnityMain(UiState* state) {
    if (state == nullptr || state->revealed) return;
    if (!ResolveMetadata(state)) {
        LogMessage("ui_reveal deferred reason=metadata");
        return;
    }
    void* target = nullptr;
    for (void* object : FindAll(state, state->game_object_class)) {
        const auto name = Invoke(state, state->get_name_method, object);
        if (name && ManagedString(*name) == "Mod" && HasHierarchy(state, object)) {
            if (target != nullptr) {
                LogMessage("ui_reveal rejected reason=target_count");
                return;
            }
            target = object;
        }
    }
    if (target == nullptr) {
        LogMessage("ui_reveal deferred reason=target_missing");
        return;
    }
    void* guard = nullptr;
    for (void* candidate : FindAll(state, state->platform_disable_class)) {
        const auto game_object = Invoke(state, state->component_get_game_object_method, candidate);
        if (game_object && *game_object != nullptr && HasHierarchy(state, *game_object)) {
            if (guard != nullptr) {
                LogMessage("ui_reveal rejected reason=guard_count");
                return;
            }
            guard = candidate;
        }
    }
    if (guard == nullptr) {
        LogMessage("ui_reveal deferred reason=guard_missing");
        return;
    }
    state->set_enabled(guard, false);
    *reinterpret_cast<bool*>(reinterpret_cast<std::byte*>(guard) + state->mobile_offset) = false;
    *reinterpret_cast<bool*>(reinterpret_cast<std::byte*>(guard) + state->switch_only_offset) = false;
    state->set_active(target, true);
    state->revealed = true;
    LogMessage("ui_reveal success path=MainUI/StartPanel/Mod memory_only=true manual_ui_only=true");
}

void OnStart(void* instance, const void* method) {
    UiState* state = g_state;
    if (state == nullptr) return;
    reinterpret_cast<StartFunction>(state->start_original)(instance, method);
    RevealOnUnityMain(state);
}

}  // namespace

UiRevealStats InstallOfficialUiRevealHook(const Il2CppApi& api, HookEngine* hooks) {
    UiRevealStats stats;
    if (hooks == nullptr || api.array_length == nullptr || api.resolve_icall == nullptr ||
        api.class_get_type == nullptr || api.type_get_object == nullptr) {
        LogMessage("ui_reveal unavailable reason=api");
        return stats;
    }
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_state != nullptr) return {true, g_state->revealed};
    if (!MatchCode(api, kStartRva, kStartBytes, sizeof(kStartBytes))) {
        LogMessage("ui_reveal unavailable reason=start_fingerprint");
        return stats;
    }
    auto state = std::make_unique<UiState>();
    state->api = &api;
    state->runtime = std::make_unique<Il2CppRuntime>(api);
    state->set_active = reinterpret_cast<UnitySetActive>(
        api.resolve_icall("UnityEngine.GameObject::SetActive(System.Boolean)"));
    state->set_enabled = reinterpret_cast<BehaviourSetEnabled>(
        api.resolve_icall("UnityEngine.Behaviour::set_enabled(System.Boolean)"));
    if (state->set_active == nullptr || state->set_enabled == nullptr) {
        LogMessage("ui_reveal unavailable reason=icall");
        return stats;
    }
    const auto target = reinterpret_cast<void*>(reinterpret_cast<std::uintptr_t>(api.image_base) + kStartRva);
    void* trampoline = nullptr;
    if (!hooks->Replace(target, reinterpret_cast<void*>(OnStart), &trampoline) || trampoline == nullptr) {
        LogMessage("ui_reveal unavailable reason=hook_install");
        return stats;
    }
    state->start_original = trampoline;
    g_state = state.release();
    stats.ready = true;
    LogMessage("ui_reveal ready gate=start_fingerprint manual_ui_only=true");
    return stats;
}

}  // namespace modloader
