#pragma once

#include "modloader/hook_engine.h"
#include "modloader/il2cpp_api.h"

#include <cstddef>
#include <string>
#include <string_view>

namespace modloader {

struct OfficialResourceUriStats {
    bool sprite_ready = false;
    bool audio_ready = false;
    bool texture_ready = false;
    std::size_t rewrites = 0;
};

OfficialResourceUriStats InstallOfficialResourceUriHooks(
    const Il2CppApi& api,
    std::string mod_root,
    HookEngine* hooks);

}  // namespace modloader
