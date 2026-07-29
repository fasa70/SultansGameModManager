#pragma once

#include "modloader/il2cpp_api.h"
#include "modloader/il2cpp_runtime.h"
#include "modloader/json_bridge.h"
#include "modloader/mod_file_index.h"

#include <cstddef>
#include <cstdint>
#include <string_view>
#include <vector>

namespace modloader {

enum class ConfigPipelineStage {
    kUpgrade,
    kRite,
    kEvent,
    kRemaining,
};

struct ConfigPipelineStats {
    std::size_t upgrade_mods = 0;
    std::size_t upgrade_committed = 0;
    std::size_t upgrade_entries = 0;
    std::size_t single_file_committed = 0;
    std::size_t single_object_committed = 0;
    std::size_t directory_committed = 0;
    std::size_t rite_committed = 0;
    std::size_t event_committed = 0;
    std::size_t entries_committed = 0;
    std::vector<std::int32_t> rite_ids_committed;
    std::size_t failed_transactions = 0;
    bool rollback_failed = false;
};

struct LoaderRuntimeContext {
    const Il2CppApi& api;
    Il2CppRuntime runtime;
    JsonBridge json;
    void* core_image = nullptr;
    void* common_image = nullptr;
    void* datapool = nullptr;
    void* config = nullptr;

    explicit LoaderRuntimeContext(const Il2CppApi& api);
    bool Resolve();
    std::optional<void*> Dictionary(std::string_view name) const;
};

class ConfigPipeline {
  public:
    ConfigPipeline(LoaderRuntimeContext& context, const ModFileIndex& index);

    ConfigPipelineStats Apply(ConfigPipelineStage stage);

  private:
    LoaderRuntimeContext& context_;
    const ModFileIndex& index_;
};

}  // namespace modloader
