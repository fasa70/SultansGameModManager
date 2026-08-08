#include "modloader/official_observer_validation.h"

#include <limits>

namespace modloader {
namespace {

bool MatchesTarget(std::uintptr_t image_base,
                   std::uintptr_t rva,
                   std::uintptr_t code) noexcept {
    return rva <= std::numeric_limits<std::uintptr_t>::max() - image_base &&
        code == image_base + rva;
}

}  // namespace

OfficialObserverValidation ValidateOfficialObserverTargets(
    const GameProfile& profile,
    std::uintptr_t image_base,
    std::uintptr_t load_global_mods_code,
    std::uintptr_t mod_loader_active_mod_code,
    std::uintptr_t mod_loader_run_code,
    bool mod_loader_run_fingerprint_matches) noexcept {
    if (load_global_mods_code == 0) {
        return OfficialObserverValidation::kLoadGlobalModsMethodCode;
    }
    if (mod_loader_active_mod_code == 0) {
        return OfficialObserverValidation::kModLoaderActiveModMethodCode;
    }
    if (mod_loader_run_code == 0) {
        return OfficialObserverValidation::kModLoaderRunMethodCode;
    }
    if (!MatchesTarget(
            image_base,
            profile.fingerprints.at(
                static_cast<std::size_t>(HookTarget::kLoadGlobalMods)).rva,
            load_global_mods_code)) {
        return OfficialObserverValidation::kLoadGlobalModsTarget;
    }
    if (!MatchesTarget(
            image_base, kOfficialModLoaderActiveModRva,
            mod_loader_active_mod_code)) {
        return OfficialObserverValidation::kModLoaderActiveModTarget;
    }
    if (!MatchesTarget(
            image_base, profile.mod_loader_run.rva,
            mod_loader_run_code)) {
        return OfficialObserverValidation::kModLoaderRunTarget;
    }
    return mod_loader_run_fingerprint_matches
        ? OfficialObserverValidation::kValid
        : OfficialObserverValidation::kModLoaderRunFingerprint;
}

const char* OfficialObserverValidationReason(
    OfficialObserverValidation validation) noexcept {
    switch (validation) {
        case OfficialObserverValidation::kValid:
            return "none";
        case OfficialObserverValidation::kLoadGlobalModsMethodCode:
            return "load_global_mods_method_code";
        case OfficialObserverValidation::kModLoaderActiveModMethodCode:
            return "mod_loader_active_mod_method_code";
        case OfficialObserverValidation::kModLoaderRunMethodCode:
            return "mod_loader_run_method_code";
        case OfficialObserverValidation::kLoadGlobalModsTarget:
            return "load_global_mods_target";
        case OfficialObserverValidation::kModLoaderActiveModTarget:
            return "mod_loader_active_mod_target";
        case OfficialObserverValidation::kModLoaderRunTarget:
            return "mod_loader_run_target";
        case OfficialObserverValidation::kModLoaderRunFingerprint:
            return "mod_loader_run_fingerprint";
    }
    return "unknown";
}

}  // namespace modloader
