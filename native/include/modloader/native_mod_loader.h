#pragma once

#include "modloader/il2cpp_api.h"

#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace modloader {

enum class ModApplyStage {
    kCards,
    kUpgrade,
    kRite,
    kEvent,
    kRemaining,
};

struct ModApplySummary {
    std::size_t discovered_mods = 0;
    std::size_t discovered_config_files = 0;
    std::size_t discovered_image_files = 0;
    std::size_t discovered_audio_files = 0;
    std::size_t rejected_entries = 0;
    std::size_t skipped_unsupported_configs = 0;
    bool abi_ready = false;
    bool json_bridge_ready = false;
    bool card_dictionary_ready = false;
    std::size_t card_mods_discovered = 0;
    std::size_t card_mods_committed = 0;
    std::size_t card_entries_committed = 0;
    std::size_t card_mods_failed = 0;
    bool card_rollback_failed = false;
    std::size_t upgrade_mods = 0;
    std::size_t upgrade_committed = 0;
    std::size_t upgrade_entries = 0;
    std::size_t single_file_committed = 0;
    std::size_t directory_committed = 0;
    std::size_t rite_committed = 0;
    std::size_t event_committed = 0;
    std::size_t config_entries_committed = 0;
    std::size_t config_failed_transactions = 0;
    bool config_rollback_failed = false;
    std::size_t resource_audio_preloads = 0;
    bool image_hooks_ready = false;
    bool audio_hooks_ready = false;
};

struct CommittedModIds {
    std::vector<std::int32_t> cards;
    std::vector<std::int32_t> rites;
};

CommittedModIds GetCommittedModIds();

class NativeModLoader {
  public:
    NativeModLoader(const Il2CppApi& api, std::string_view mod_root);

    ModApplySummary Apply(ModApplyStage stage);

  private:
    const Il2CppApi& api_;
    std::string mod_root_;
};

}  // namespace modloader
