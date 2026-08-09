#pragma once

#include "modloader/hook_engine.h"
#include "modloader/il2cpp_api.h"

namespace modloader {

struct UiRevealStats {
    bool ready = false;
    bool revealed = false;
};

// Installs a narrow UnityMain gate. The hook only reveals the uniquely matched
// MainUI/StartPanel/Mod object and never invokes any Mod activation entrypoint.
UiRevealStats InstallOfficialUiRevealHook(const Il2CppApi& api, HookEngine* hooks);

}  // namespace modloader
