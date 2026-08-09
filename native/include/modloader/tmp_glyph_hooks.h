#pragma once

#include "modloader/hook_engine.h"
#include "modloader/il2cpp_api.h"

#include <cstddef>

namespace modloader {

struct TmpGlyphHookStats {
    bool ready = false;
    bool fingerprint_matched = false;
    std::size_t rewrites = 0;
};

TmpGlyphHookStats InstallTmpGlyphHook(
    const Il2CppApi& api,
    HookEngine* hooks);

}  // namespace modloader
