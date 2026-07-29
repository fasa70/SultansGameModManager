#include "modloader/native_mod_loader.h"

#include "modloader/config_pipeline.h"
#include "modloader/canonical_dictionary.h"
#include "modloader/config_catalog.h"
#include "modloader/il2cpp_runtime.h"
#include "modloader/json_bridge.h"
#include "modloader/managed_dictionary.h"
#include "modloader/mod_file_index.h"
#include "modloader/resource_overrides.h"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string_view>
#include <utility>
#include <vector>

namespace modloader {
namespace {

constexpr std::size_t kMaximumConfigFileSize = 16U * 1024U * 1024U;

std::mutex& RetainedHandlesMutex() {
    static auto* mutex = new std::mutex();
    return *mutex;
}

std::vector<GcHandle>& RetainedHandles() {
    static auto* handles = new std::vector<GcHandle>();
    return *handles;
}

std::mutex& CommittedIdsMutex() {
    static auto* mutex = new std::mutex();
    return *mutex;
}

CommittedModIds& CommittedIds() {
    static auto* ids = new CommittedModIds();
    return *ids;
}

void AppendUnique(std::vector<std::int32_t>* target,
                  const std::vector<std::int32_t>& source) {
    for (const std::int32_t id : source) {
        if (std::find(target->begin(), target->end(), id) == target->end()) {
            target->push_back(id);
        }
    }
}

void RetainCommitted(std::vector<GcHandle>* handles) {
    std::lock_guard<std::mutex> lock(RetainedHandlesMutex());
    auto& retained = RetainedHandles();
    for (GcHandle& handle : *handles) {
        retained.push_back(std::move(handle));
    }
    handles->clear();
}

bool IsConfigFile(const IndexedModFile& file) {
    constexpr std::string_view prefix = "config/";
    return file.relative_path.size() > prefix.size() &&
        file.relative_path.compare(0, prefix.size(), prefix) == 0;
}

std::string_view ConfigRelativePath(const IndexedModFile& file) {
    constexpr std::string_view prefix = "config/";
    return std::string_view(file.relative_path).substr(prefix.size());
}

const IndexedModFile* FindConfigFile(const IndexedMod& mod, std::string_view path) {
    const auto iterator = std::find_if(
        mod.config_files.begin(), mod.config_files.end(),
        [path](const IndexedModFile& file) { return file.relative_path == path; });
    return iterator == mod.config_files.end() ? nullptr : &*iterator;
}

std::optional<void*> FindDatapool(const Il2CppRuntime& runtime, void* core_image) {
    const auto application = runtime.FindClass(core_image, {"", "Il2Cpp"}, "GameApplication");
    if (!application.has_value()) {
        return std::nullopt;
    }
    return runtime.StaticFieldValue(*application, "datapool");
}

bool HasVerifiedCoreAbi(const Il2CppRuntime& runtime) {
    const auto core_image = runtime.FindImage({"Core.dll", "Il2CppCore.dll"});
    if (!core_image.has_value()) {
        return false;
    }
    const auto datapool = runtime.FindClass(*core_image, {"", "Il2Cpp"}, "Datapool");
    if (!datapool.has_value()) {
        return false;
    }
    const auto config_field = runtime.FieldOffset(*datapool, "<config>k__BackingField");
    const auto rite_post_process = runtime.FindMethod(*datapool, "LoadRitePostProcess", 0);
    const auto event_post_process = runtime.FindMethod(*datapool, "LoadEventPostProcess", 0);
    return config_field.has_value() && rite_post_process.has_value() && event_post_process.has_value();
}

struct CardContext {
    void* datapool = nullptr;
    void* cards = nullptr;
    void* get_card = nullptr;
};

std::optional<CardContext> FindCardContext(const Il2CppRuntime& runtime) {
    const auto core_image = runtime.FindImage({"Core.dll", "Il2CppCore.dll"});
    if (!core_image.has_value()) {
        return std::nullopt;
    }
    const auto datapool = FindDatapool(runtime, *core_image);
    if (!datapool.has_value()) {
        return std::nullopt;
    }
    const auto config = runtime.InstanceFieldValue(*datapool, "<config>k__BackingField");
    if (!config.has_value()) {
        return std::nullopt;
    }
    const auto cards = runtime.InstanceFieldValue(*config, "<card>k__BackingField");
    const auto datapool_class = runtime.ObjectClass(*datapool);
    if (!cards.has_value() || !datapool_class.has_value()) {
        return std::nullopt;
    }
    const auto get_card = runtime.FindMethod(*datapool_class, "GetCardData", 1);
    if (!get_card.has_value()) {
        return std::nullopt;
    }
    ManagedIntDictionary dictionary(runtime);
    if (!dictionary.Probe(*cards)) {
        return std::nullopt;
    }
    return CardContext{*datapool, *cards, *get_card};
}

bool ValidateCardNode(const Il2CppRuntime& runtime, const Il2CppApi& api,
                      void* node, std::int32_t expected_id) {
    const auto klass = runtime.ObjectClass(node);
    if (!klass.has_value() || api.class_get_name == nullptr) {
        return false;
    }
    const char* class_name = api.class_get_name(*klass);
    if (class_name == nullptr || std::string_view(class_name) != "CardNode") {
        return false;
    }

    const auto id_offset = runtime.FieldOffset(*klass, "<id>k__BackingField");
    if (!id_offset.has_value() ||
        *reinterpret_cast<const std::int32_t*>(
            reinterpret_cast<const std::byte*>(node) + *id_offset) != expected_id) {
        return false;
    }

    constexpr std::string_view required_fields[] = {
        "<name>k__BackingField",
        "<title>k__BackingField",
        "<text>k__BackingField",
        "<pops>k__BackingField",
        "<type>k__BackingField",
        "<resource>k__BackingField",
        "<tag>k__BackingField",
        "<vanish>k__BackingField",
        "<equips>k__BackingField",
    };
    for (const std::string_view field_name : required_fields) {
        const auto offset = runtime.FieldOffset(*klass, field_name);
        if (!offset.has_value() ||
            *reinterpret_cast<void* const*>(
                reinterpret_cast<const std::byte*>(node) + *offset) == nullptr) {
            return false;
        }
    }
    return true;
}

bool VerifyTemporaryRemove(ManagedIntDictionary* dictionary, std::int32_t key,
                           void* original) {
    bool contains = false;
    if (!dictionary->Remove(key) ||
        !dictionary->TryContains(key, &contains) || contains ||
        !dictionary->Set(key, original)) {
        return false;
    }
    const auto restored = dictionary->Get(key);
    return restored.has_value() && *restored == original;
}

bool ApplyCardMod(const Il2CppRuntime& runtime, const Il2CppApi& api,
                  JsonBridge* json, const CardContext& context,
                  const IndexedModFile& file, bool* remove_verified,
                  ModApplySummary* summary) {
    const auto contents = ReadIndexedFile(file, kMaximumConfigFileSize);
    if (!contents.has_value()) {
        return false;
    }
    const std::vector<std::int32_t> ids = DiscoverTopLevelIntegerKeys(*contents);
    if (ids.empty()) {
        return false;
    }

    const auto temporary = json->DeserializeInto(context.cards, *contents);
    if (!temporary.has_value()) {
        return false;
    }
    const auto canonical_class = runtime.ObjectClass(context.cards);
    const auto temporary_class = runtime.ObjectClass(*temporary);
    if (!canonical_class.has_value() || !temporary_class.has_value() ||
        *canonical_class != *temporary_class) {
        return false;
    }

    std::vector<GcHandle> committed_handles;
    committed_handles.push_back(runtime.Retain(*temporary, false));
    if (!committed_handles.back().valid()) {
        return false;
    }

    ManagedIntDictionary temporary_dictionary(runtime);
    ManagedIntDictionary canonical_dictionary(runtime);
    if (!temporary_dictionary.Probe(*temporary) ||
        !canonical_dictionary.Probe(context.cards)) {
        return false;
    }

    std::vector<std::pair<std::int32_t, void*>> entries;
    entries.reserve(ids.size());
    for (const std::int32_t id : ids) {
        const auto node = temporary_dictionary.Get(id);
        if (!node.has_value() || !ValidateCardNode(runtime, api, *node, id)) {
            return false;
        }
        committed_handles.push_back(runtime.Retain(*node, false));
        if (!committed_handles.back().valid()) {
            return false;
        }
        entries.emplace_back(id, *node);
    }

    if (!*remove_verified &&
        !VerifyTemporaryRemove(&temporary_dictionary, entries.front().first,
                               entries.front().second)) {
        return false;
    }
    *remove_verified = true;

    std::vector<GcHandle> original_handles;
    IntDictionaryOperations operations{
        [&](std::int32_t key, bool* exists, void** value) {
            if (!canonical_dictionary.TryContains(key, exists)) {
                return false;
            }
            if (!*exists) {
                *value = nullptr;
                return true;
            }
            const auto previous = canonical_dictionary.Get(key);
            if (!previous.has_value()) {
                return false;
            }
            GcHandle handle = runtime.Retain(*previous, false);
            if (!handle.valid()) {
                return false;
            }
            *value = *previous;
            original_handles.push_back(std::move(handle));
            return true;
        },
        [&](std::int32_t key, void* value) {
            return canonical_dictionary.Set(key, value);
        },
        [&](std::int32_t key) {
            return canonical_dictionary.Remove(key);
        },
        [&](std::int32_t key, void* value) {
            void* parameters[] = {&key};
            const auto actual = runtime.Invoke(context.get_card, context.datapool, parameters);
            return actual.has_value() && *actual == value;
        },
        [&](std::int32_t key) {
            bool contains = true;
            return canonical_dictionary.TryContains(key, &contains) && !contains;
        },
    };

    CanonicalIntDictionaryTransaction transaction(std::move(operations));
    for (const auto& [id, node] : entries) {
        if (!transaction.Apply(id, node)) {
            if (transaction.result() == TransactionResult::kRollbackFailed) {
                summary->card_rollback_failed = true;
            }
            return false;
        }
    }
    if (transaction.Commit() != TransactionResult::kCommitted) {
        return false;
    }

    summary->card_entries_committed += entries.size();
    {
        std::lock_guard<std::mutex> lock(CommittedIdsMutex());
        auto& target = CommittedIds().cards;
        for (const auto& [id, node] : entries) {
            static_cast<void>(node);
            if (std::find(target.begin(), target.end(), id) == target.end()) {
                target.push_back(id);
            }
        }
    }
    RetainCommitted(&committed_handles);
    return true;
}

}  // namespace

CommittedModIds GetCommittedModIds() {
    std::lock_guard<std::mutex> lock(CommittedIdsMutex());
    return CommittedIds();
}

NativeModLoader::NativeModLoader(const Il2CppApi& api, std::string_view mod_root)
    : api_(api), mod_root_(mod_root) {}

ModApplySummary NativeModLoader::Apply(ModApplyStage stage) {
    ModApplySummary summary;
    const ModFileIndex index = ScanModRoot(mod_root_);
    summary.discovered_mods = index.mods.size();
    summary.rejected_entries = index.rejected_entries.size();
    const ResourceOverrideIndex resources = BuildResourceOverrideIndex(index);
    summary.discovered_image_files = resources.images.size();
    summary.discovered_audio_files = resources.audio.size();
    summary.rejected_entries += resources.rejected;
    for (const IndexedMod& mod : index.mods) {
        summary.discovered_config_files += mod.config_files.size();
        for (const IndexedModFile& file : mod.config_files) {
            if (IsConfigFile(file) && IsExplicitlyUnsupportedConfig(ConfigRelativePath(file))) {
                ++summary.skipped_unsupported_configs;
            }
        }
        if (FindConfigFile(mod, "config/cards.json") != nullptr) {
            ++summary.card_mods_discovered;
        }
    }

    Il2CppAttachedThread attached(api_);
    if (!attached.attached()) {
        return summary;
    }
    Il2CppRuntime runtime(api_);
    summary.abi_ready = HasVerifiedCoreAbi(runtime);
    if (!summary.abi_ready) {
        return summary;
    }

    if (stage == ModApplyStage::kCards) {
        JsonBridge json(runtime);
        summary.json_bridge_ready = json.Probe();
        const auto card_context = FindCardContext(runtime);
        summary.card_dictionary_ready = card_context.has_value();
        if (!summary.json_bridge_ready || !card_context.has_value()) {
            return summary;
        }

        bool remove_verified = false;
        for (const IndexedMod& mod : index.mods) {
            const IndexedModFile* cards = FindConfigFile(mod, "config/cards.json");
            if (cards == nullptr) {
                continue;
            }
            if (ApplyCardMod(runtime, api_, &json, *card_context, *cards,
                             &remove_verified, &summary)) {
                ++summary.card_mods_committed;
            } else {
                ++summary.card_mods_failed;
                if (summary.card_rollback_failed) {
                    break;
                }
            }
        }
        return summary;
    }

    LoaderRuntimeContext pipeline_context(api_);
    if (pipeline_context.Resolve()) {
        ConfigPipelineStage pipeline_stage = ConfigPipelineStage::kRemaining;
        if (stage == ModApplyStage::kUpgrade) {
            pipeline_stage = ConfigPipelineStage::kUpgrade;
        } else if (stage == ModApplyStage::kRite) {
            pipeline_stage = ConfigPipelineStage::kRite;
        } else if (stage == ModApplyStage::kEvent) {
            pipeline_stage = ConfigPipelineStage::kEvent;
        }
        const ConfigPipelineStats pipeline =
            ConfigPipeline(pipeline_context, index).Apply(pipeline_stage);
        summary.json_bridge_ready = true;
        summary.upgrade_mods = pipeline.upgrade_mods;
        summary.upgrade_committed = pipeline.upgrade_committed;
        summary.upgrade_entries = pipeline.upgrade_entries;
        summary.single_file_committed = pipeline.single_file_committed;
        summary.directory_committed = pipeline.directory_committed;
        summary.rite_committed = pipeline.rite_committed;
        summary.event_committed = pipeline.event_committed;
        summary.config_entries_committed = pipeline.entries_committed;
        {
            std::lock_guard<std::mutex> lock(CommittedIdsMutex());
            AppendUnique(&CommittedIds().rites, pipeline.rite_ids_committed);
        }
        summary.config_failed_transactions = pipeline.failed_transactions;
        summary.config_rollback_failed = pipeline.rollback_failed;
    }
    return summary;
}

}  // namespace modloader
