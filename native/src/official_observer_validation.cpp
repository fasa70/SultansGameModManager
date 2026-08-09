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


OfficialUiObserverValidation ValidateOfficialUiObserverTargets(
    const GameProfile& profile,
    std::uintptr_t image_base,
    std::uintptr_t panel_on_enable_code,
    std::uintptr_t panel_show_mods_code,
    std::uintptr_t panel_refresh_mods_code,
    std::uintptr_t item_setup_code,
    bool panel_on_enable_fingerprint_matches,
    bool panel_show_mods_fingerprint_matches,
    bool panel_refresh_mods_fingerprint_matches,
    bool item_setup_fingerprint_matches) noexcept {
    if (panel_on_enable_code == 0) {
        return OfficialUiObserverValidation::kPanelOnEnableMethodCode;
    }
    if (panel_show_mods_code == 0) {
        return OfficialUiObserverValidation::kPanelShowModsMethodCode;
    }
    if (panel_refresh_mods_code == 0) {
        return OfficialUiObserverValidation::kPanelRefreshModsMethodCode;
    }
    if (item_setup_code == 0) {
        return OfficialUiObserverValidation::kItemSetupMethodCode;
    }
    const OfficialUiObserverProfile& targets = profile.ui_observer;
    if (!MatchesTarget(image_base, targets.panel_on_enable.rva, panel_on_enable_code)) {
        return OfficialUiObserverValidation::kPanelOnEnableTarget;
    }
    if (!MatchesTarget(image_base, targets.panel_show_mods.rva, panel_show_mods_code)) {
        return OfficialUiObserverValidation::kPanelShowModsTarget;
    }
    if (!MatchesTarget(image_base, targets.panel_refresh_mods.rva, panel_refresh_mods_code)) {
        return OfficialUiObserverValidation::kPanelRefreshModsTarget;
    }
    if (!MatchesTarget(image_base, targets.item_setup.rva, item_setup_code)) {
        return OfficialUiObserverValidation::kItemSetupTarget;
    }
    if (!panel_on_enable_fingerprint_matches) {
        return OfficialUiObserverValidation::kPanelOnEnableFingerprint;
    }
    if (!panel_show_mods_fingerprint_matches) {
        return OfficialUiObserverValidation::kPanelShowModsFingerprint;
    }
    if (!panel_refresh_mods_fingerprint_matches) {
        return OfficialUiObserverValidation::kPanelRefreshModsFingerprint;
    }
    return item_setup_fingerprint_matches
        ? OfficialUiObserverValidation::kValid
        : OfficialUiObserverValidation::kItemSetupFingerprint;
}

const char* OfficialUiObserverValidationReason(
    OfficialUiObserverValidation validation) noexcept {
    switch (validation) {
        case OfficialUiObserverValidation::kValid:
            return "none";
        case OfficialUiObserverValidation::kPanelOnEnableMethodCode:
            return "panel_on_enable_method_code";
        case OfficialUiObserverValidation::kPanelShowModsMethodCode:
            return "panel_show_mods_method_code";
        case OfficialUiObserverValidation::kPanelRefreshModsMethodCode:
            return "panel_refresh_mods_method_code";
        case OfficialUiObserverValidation::kItemSetupMethodCode:
            return "item_setup_method_code";
        case OfficialUiObserverValidation::kPanelOnEnableTarget:
            return "panel_on_enable_target";
        case OfficialUiObserverValidation::kPanelShowModsTarget:
            return "panel_show_mods_target";
        case OfficialUiObserverValidation::kPanelRefreshModsTarget:
            return "panel_refresh_mods_target";
        case OfficialUiObserverValidation::kItemSetupTarget:
            return "item_setup_target";
        case OfficialUiObserverValidation::kPanelOnEnableFingerprint:
            return "panel_on_enable_fingerprint";
        case OfficialUiObserverValidation::kPanelShowModsFingerprint:
            return "panel_show_mods_fingerprint";
        case OfficialUiObserverValidation::kPanelRefreshModsFingerprint:
            return "panel_refresh_mods_fingerprint";
        case OfficialUiObserverValidation::kItemSetupFingerprint:
            return "item_setup_fingerprint";
    }
    return "unknown";
}

}  // namespace modloader
