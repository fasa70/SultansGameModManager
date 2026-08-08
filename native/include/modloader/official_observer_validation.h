#pragma once

#include "modloader/game_profile.h"

#include <cstdint>

namespace modloader {

constexpr std::uintptr_t kOfficialModLoaderActiveModRva = 0x1e88ef0;

enum class OfficialObserverValidation : std::uint8_t {
    kValid = 0,
    kLoadGlobalModsMethodCode,
    kModLoaderActiveModMethodCode,
    kModLoaderRunMethodCode,
    kLoadGlobalModsTarget,
    kModLoaderActiveModTarget,
    kModLoaderRunTarget,
    kModLoaderRunFingerprint,
};

OfficialObserverValidation ValidateOfficialObserverTargets(
    const GameProfile& profile,
    std::uintptr_t image_base,
    std::uintptr_t load_global_mods_code,
    std::uintptr_t mod_loader_active_mod_code,
    std::uintptr_t mod_loader_run_code,
    bool mod_loader_run_fingerprint_matches) noexcept;

const char* OfficialObserverValidationReason(
    OfficialObserverValidation validation) noexcept;

}  // namespace modloader
