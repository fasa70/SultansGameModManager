#pragma once

#include "modloader/il2cpp_api.h"
#include "modloader/runtime_state.h"

namespace modloader {

bool InstallModHooks(const Il2CppApi& api, RuntimeController& runtime);

}  // namespace modloader
