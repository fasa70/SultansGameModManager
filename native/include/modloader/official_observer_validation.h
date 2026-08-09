#pragma once

#include "modloader/game_profile.h"

#include <cstdint>
#include <string>
#include <string_view>

namespace modloader {

constexpr std::string_view kOfficialUiItemSetupNodeType = "ModNode";
constexpr std::string_view kOfficialUiItemSetupPanelType = "ModPanelController";
constexpr std::string_view kOfficialUiPanelModsField = "mods";

constexpr std::uintptr_t kOfficialModLoaderActiveModRva = 0x1e88ef0;

enum class OfficialUiObserverValidation : std::uint8_t {
    kValid = 0,
    kPanelOnEnableMethodCode,
    kPanelShowModsMethodCode,
    kPanelRefreshModsMethodCode,
    kItemSetupMethodCode,
    kPanelOnEnableTarget,
    kPanelShowModsTarget,
    kPanelRefreshModsTarget,
    kItemSetupTarget,
    kPanelOnEnableFingerprint,
    kPanelShowModsFingerprint,
    kPanelRefreshModsFingerprint,
    kItemSetupFingerprint,
};

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
    bool item_setup_fingerprint_matches) noexcept;

const char* OfficialUiObserverValidationReason(
    OfficialUiObserverValidation validation) noexcept;

struct OfficialUiObserverMembers {
    bool panel_on_enable;
    bool panel_show_mods;
    bool panel_refresh_mods;
    bool item_setup;
    bool panel_mods;
};

bool OfficialUiObserverMembersReady(
    const OfficialUiObserverMembers& members) noexcept;

std::string OfficialUiObserverMissingMembers(
    const OfficialUiObserverMembers& members);

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
