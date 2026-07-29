#pragma once

#include "modloader/hook_engine.h"
#include "modloader/il2cpp_api.h"
#include "modloader/resource_overrides.h"

#include <cstddef>

namespace modloader {

struct ResourceHookStats {
    std::size_t image_paths = 0;
    std::size_t audio_paths = 0;
    std::size_t image_collisions = 0;
    std::size_t rejected_resources = 0;
    std::size_t audio_preloads = 0;
    bool image_hooks_ready = false;
    bool audio_hooks_ready = false;
};

ResourceHookStats InstallResourceHooks(const Il2CppApi& api,
                                       const ResourceOverrideIndex& resources,
                                       HookEngine* hooks);

}  // namespace modloader
