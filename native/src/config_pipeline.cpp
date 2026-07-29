#include "modloader/config_pipeline.h"

#include "modloader/android_log.h"
#include "modloader/canonical_dictionary.h"
#include "modloader/config_catalog.h"
#include "modloader/managed_dictionary.h"
#include "modloader/node_post_process.h"

#include <algorithm>
#include <cctype>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <limits>
#include <memory>
#include <cstring>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace modloader {
namespace {

constexpr std::size_t kMaximumConfigFileSize = 16U * 1024U * 1024U;

std::mutex& HandleMutex() {
    static auto* mutex = new std::mutex();
    return *mutex;
}

std::vector<GcHandle>& Handles() {
    static auto* handles = new std::vector<GcHandle>();
    return *handles;
}

void Adopt(std::vector<GcHandle>* handles) {
    std::lock_guard<std::mutex> lock(HandleMutex());
    for (GcHandle& handle : *handles) {
        Handles().push_back(std::move(handle));
    }
    handles->clear();
}

bool Retain(const Il2CppRuntime& runtime, void* object, std::vector<GcHandle>* handles) {
    GcHandle handle = runtime.Retain(object, false);
    if (!handle.valid()) {
        return false;
    }
    handles->push_back(std::move(handle));
    return true;
}

std::string_view PrepareStageName(NodePrepareStage stage) {
    switch (stage) {
        case NodePrepareStage::kReady:
            return "ready";
        case NodePrepareStage::kInvalidArgument:
            return "invalid_argument";
        case NodePrepareStage::kProbeFailed:
            return "probe_failed";
        case NodePrepareStage::kRiteSlots:
            return "rite_slots";
        case NodePrepareStage::kRiteTagTranslation:
            return "rite_tag_translation";
        case NodePrepareStage::kEventTimingIdentify:
            return "event_timing_identify";
        case NodePrepareStage::kEventSettlementArray:
            return "event_settlement_array";
        case NodePrepareStage::kEventSettlementUpdate:
            return "event_settlement_update";
        case NodePrepareStage::kEventTimingScan:
            return "event_timing_scan";
    }
    return "unknown";
}

const IndexedModFile* FindFile(const IndexedMod& mod, std::string_view path) {
    const auto iterator = std::find_if(
        mod.config_files.begin(), mod.config_files.end(),
        [path](const IndexedModFile& file) { return file.relative_path == path; });
    return iterator == mod.config_files.end() ? nullptr : &*iterator;
}

std::vector<const IndexedModFile*> FindDirectoryFiles(const IndexedMod& mod,
                                                      std::string_view directory) {
    const std::string prefix = "config/" + std::string(directory) + "/";
    std::vector<const IndexedModFile*> files;
    for (const IndexedModFile& file : mod.config_files) {
        if (file.relative_path.size() <= prefix.size() ||
            file.relative_path.compare(0, prefix.size(), prefix) != 0) {
            continue;
        }
        const std::string_view name(file.relative_path.data() + prefix.size(),
                                    file.relative_path.size() - prefix.size());
        if (name.find('/') == std::string_view::npos && name.size() > 5 &&
            name.substr(name.size() - 5) == ".json") {
            files.push_back(&file);
        }
    }
    return files;
}

std::optional<std::int32_t> ParseIntegerFileKey(const IndexedModFile& file) {
    const std::size_t slash = file.relative_path.find_last_of('/');
    const std::size_t extension = file.relative_path.rfind(".json");
    if (extension == std::string::npos || extension <= slash + 1) {
        return std::nullopt;
    }
    std::int64_t value = 0;
    for (std::size_t index = slash + 1; index < extension; ++index) {
        const char digit = file.relative_path[index];
        if (digit < '0' || digit > '9') {
            return std::nullopt;
        }
        value = value * 10 + (digit - '0');
        if (value > std::numeric_limits<std::int32_t>::max()) {
            return std::nullopt;
        }
    }
    return static_cast<std::int32_t>(value);
}

std::optional<std::string> ParseStringFileKey(const IndexedModFile& file) {
    const std::size_t slash = file.relative_path.find_last_of('/');
    const std::size_t extension = file.relative_path.rfind(".json");
    if (extension == std::string::npos || extension <= slash + 1) {
        return std::nullopt;
    }
    return file.relative_path.substr(slash + 1, extension - slash - 1);
}

bool SameManagedClass(const Il2CppRuntime& runtime, void* left, void* right) {
    const auto left_class = runtime.ObjectClass(left);
    const auto right_class = runtime.ObjectClass(right);
    return left_class.has_value() && right_class.has_value() &&
        *left_class == *right_class;
}

bool ValidateClass(const LoaderRuntimeContext& context, void* object,
                   std::string_view expected) {
    const auto klass = context.runtime.ObjectClass(object);
    if (!klass.has_value() || context.api.class_get_name == nullptr) {
        return false;
    }
    const char* name = context.api.class_get_name(*klass);
    return name != nullptr && std::string_view(name) == expected;
}

bool ValidateUpgradeNode(const LoaderRuntimeContext& context, void* object,
                         std::int32_t expected) {
    if (!ValidateClass(context, object, "UpgradeNode")) {
        return false;
    }
    return *reinterpret_cast<const std::int32_t*>(
        reinterpret_cast<const std::byte*>(object) + 0x18) == expected;
}

bool ValidateIdField(const LoaderRuntimeContext& context, void* object,
                     std::int32_t expected) {
    const auto klass = context.runtime.ObjectClass(object);
    if (!klass.has_value()) {
        return false;
    }
    const auto offset = context.runtime.FieldOffset(*klass, "<id>k__BackingField");
    return offset.has_value() &&
        *reinterpret_cast<const std::int32_t*>(
            reinterpret_cast<const std::byte*>(object) + *offset) == expected;
}

bool ApplyIntEntries(LoaderRuntimeContext& context, void* canonical,
                     const std::vector<std::pair<std::int32_t, void*>>& entries,
                     void* authoritative_getter, std::vector<GcHandle>* committed,
                     ConfigPipelineStats* stats) {
    ManagedIntDictionary dictionary(context.runtime);
    if (!dictionary.Probe(canonical)) {
        return false;
    }
    std::vector<GcHandle> original_handles;
    IntDictionaryOperations operations{
        [&](std::int32_t key, bool* exists, void** value) {
            if (!dictionary.TryContains(key, exists)) {
                return false;
            }
            if (!*exists) {
                *value = nullptr;
                return true;
            }
            const auto previous = dictionary.Get(key);
            if (!previous.has_value() ||
                !Retain(context.runtime, *previous, &original_handles)) {
                return false;
            }
            *value = *previous;
            return true;
        },
        [&](std::int32_t key, void* value) { return dictionary.Set(key, value); },
        [&](std::int32_t key) { return dictionary.Remove(key); },
        [&](std::int32_t key, void* value) {
            if (authoritative_getter == nullptr) {
                const auto actual = dictionary.Get(key);
                return actual.has_value() && *actual == value;
            }
            void* parameters[] = {&key};
            const auto actual = context.runtime.Invoke(
                authoritative_getter, context.datapool, parameters);
            return actual.has_value() && *actual == value;
        },
        [&](std::int32_t key) {
            bool contains = true;
            return dictionary.TryContains(key, &contains) && !contains;
        },
    };
    CanonicalIntDictionaryTransaction transaction(std::move(operations));
    for (const auto& [key, value] : entries) {
        if (!transaction.Apply(key, value)) {
            if (transaction.result() == TransactionResult::kRollbackFailed) {
                stats->rollback_failed = true;
            }
            return false;
        }
    }
    if (transaction.Commit() != TransactionResult::kCommitted) {
        return false;
    }
    stats->entries_committed += entries.size();
    Adopt(committed);
    return true;
}

bool ApplyStringEntries(LoaderRuntimeContext& context, void* canonical,
                        const std::vector<std::pair<std::string, void*>>& entries,
                        std::vector<GcHandle>* committed,
                        ConfigPipelineStats* stats) {
    ManagedStringDictionary dictionary(context.runtime);
    if (!dictionary.Probe(canonical)) {
        return false;
    }
    std::vector<GcHandle> original_handles;
    DictionaryOperations operations{
        [&](const std::string& key, bool* exists, void** value) {
            if (!dictionary.TryContains(key, exists)) {
                return false;
            }
            if (!*exists) {
                *value = nullptr;
                return true;
            }
            const auto previous = dictionary.Get(key);
            if (!previous.has_value() ||
                !Retain(context.runtime, *previous, &original_handles)) {
                return false;
            }
            *value = *previous;
            return true;
        },
        [&](const std::string& key, void* value) { return dictionary.Set(key, value); },
        [&](const std::string& key) { return dictionary.Remove(key); },
        [&](const std::string& key, void* value) {
            const auto actual = dictionary.Get(key);
            return actual.has_value() && *actual == value;
        },
        [&](const std::string& key) {
            bool contains = true;
            return dictionary.TryContains(key, &contains) && !contains;
        },
    };
    CanonicalDictionaryTransaction transaction(std::move(operations));
    for (const auto& [key, value] : entries) {
        if (!transaction.Apply(key, value)) {
            if (transaction.result() == TransactionResult::kRollbackFailed) {
                stats->rollback_failed = true;
            }
            return false;
        }
    }
    if (transaction.Commit() != TransactionResult::kCommitted) {
        return false;
    }
    stats->entries_committed += entries.size();
    Adopt(committed);
    return true;
}

bool ApplyIntSingleFile(LoaderRuntimeContext& context, const IndexedModFile& file,
                        void* canonical, std::string_view expected_class,
                        ConfigPipelineStats* stats) {
    const auto contents = ReadIndexedFile(file, kMaximumConfigFileSize);
    if (!contents.has_value()) {
        return false;
    }
    const std::vector<std::int32_t> keys = DiscoverTopLevelIntegerKeys(*contents);
    const auto temporary = context.json.DeserializeInto(canonical, *contents);
    if (keys.empty() || !temporary.has_value() ||
        !SameManagedClass(context.runtime, canonical, *temporary)) {
        return false;
    }
    std::vector<GcHandle> handles;
    if (!Retain(context.runtime, *temporary, &handles)) {
        return false;
    }
    ManagedIntDictionary temporary_dictionary(context.runtime);
    if (!temporary_dictionary.Probe(*temporary)) {
        return false;
    }
    std::vector<std::pair<std::int32_t, void*>> entries;
    for (const std::int32_t key : keys) {
        const auto value = temporary_dictionary.Get(key);
        if (!value.has_value() ||
            (expected_class == "UpgradeNode" &&
             !ValidateUpgradeNode(context, *value, key)) ||
            (!expected_class.empty() && expected_class != "UpgradeNode" &&
             (!ValidateClass(context, *value, expected_class) ||
              !ValidateIdField(context, *value, key))) ||
            !Retain(context.runtime, *value, &handles)) {
            return false;
        }
        entries.emplace_back(key, *value);
    }
    return ApplyIntEntries(context, canonical, entries, nullptr, &handles, stats);
}

bool ApplyStringSingleFile(LoaderRuntimeContext& context, const IndexedModFile& file,
                           void* canonical, ConfigPipelineStats* stats) {
    const auto contents = ReadIndexedFile(file, kMaximumConfigFileSize);
    if (!contents.has_value()) {
        return false;
    }
    const std::vector<std::string> keys = DiscoverTopLevelStringKeys(*contents);
    const auto temporary = context.json.DeserializeInto(canonical, *contents);
    if (keys.empty() || !temporary.has_value() ||
        !SameManagedClass(context.runtime, canonical, *temporary)) {
        return false;
    }
    std::vector<GcHandle> handles;
    if (!Retain(context.runtime, *temporary, &handles)) {
        return false;
    }
    ManagedStringDictionary temporary_dictionary(context.runtime);
    if (!temporary_dictionary.Probe(*temporary)) {
        return false;
    }
    std::vector<std::pair<std::string, void*>> entries;
    for (const std::string& key : keys) {
        const auto value = temporary_dictionary.Get(key);
        if (!value.has_value() || !Retain(context.runtime, *value, &handles)) {
            return false;
        }
        entries.emplace_back(key, *value);
    }
    return ApplyStringEntries(context, canonical, entries, &handles, stats);
}

bool ApplySingleObject(LoaderRuntimeContext& context, const IndexedModFile& file,
                       const SingleObjectConfig& spec, void* canonical,
                       ConfigPipelineStats* stats) {
    static_cast<void>(spec);
    const auto contents = ReadIndexedFile(file, kMaximumConfigFileSize);
    if (!contents.has_value()) {
        return false;
    }
    const auto temporary = context.json.DeserializeInto(canonical, *contents);
    if (!temporary.has_value() || !SameManagedClass(context.runtime, canonical, *temporary)) {
        return false;
    }

    const auto canonical_class = context.runtime.ObjectClass(canonical);
    if (!canonical_class.has_value()) {
        return false;
    }
    const auto names = DiscoverTopLevelStringKeys(*contents);
    if (names.empty()) {
        return false;
    }
    const std::vector<Il2CppRuntime::InstanceField> fields =
        context.runtime.InstanceFields(*canonical_class);
    std::vector<GcHandle> handles;
    if (!Retain(context.runtime, *temporary, &handles)) {
        return false;
    }

    struct Mutation {
        Il2CppRuntime::InstanceField field;
        std::vector<std::byte> previous;
    };
    std::vector<Mutation> journal;
    for (const std::string& name : names) {
        const std::string backing = "<" + name + ">k__BackingField";
        const auto field = std::find_if(fields.begin(), fields.end(), [&](const auto& candidate) {
            return candidate.name == backing;
        });
        if (field == fields.end()) {
            goto rollback;
        }
        auto* canonical_value = reinterpret_cast<std::byte*>(canonical) + field->offset;
        auto* temporary_value = reinterpret_cast<std::byte*>(*temporary) + field->offset;
        Mutation mutation{*field, std::vector<std::byte>(canonical_value,
                                                          canonical_value + field->value_size)};
        if (field->is_reference) {
            void* previous = *reinterpret_cast<void**>(canonical_value);
            void* replacement = *reinterpret_cast<void**>(temporary_value);
            if ((previous != nullptr && !Retain(context.runtime, previous, &handles)) ||
                (replacement != nullptr && !Retain(context.runtime, replacement, &handles))) {
                goto rollback;
            }
            journal.push_back(std::move(mutation));
            context.api.field_set_value(canonical, field->handle, &replacement);
        } else {
            journal.push_back(std::move(mutation));
            context.api.field_set_value(canonical, field->handle, temporary_value);
        }
        if (std::memcmp(canonical_value, temporary_value, field->value_size) != 0) {
            goto rollback;
        }
    }
    Adopt(&handles);
    ++stats->single_object_committed;
    return true;

rollback:
    bool restored = true;
    for (auto entry = journal.rbegin(); entry != journal.rend(); ++entry) {
        context.api.field_set_value(canonical, entry->field.handle, entry->previous.data());
        const auto* actual = reinterpret_cast<const std::byte*>(canonical) + entry->field.offset;
        restored = std::memcmp(actual, entry->previous.data(), entry->field.value_size) == 0 && restored;
    }
    if (!restored) {
        stats->rollback_failed = true;
    }
    return false;
}
std::optional<std::string_view> FindTopLevelObjectValue(std::string_view json,
                                                         std::string_view expected_key) {
    std::int32_t depth = 0;
    bool in_string = false;
    bool escaped = false;
    for (std::size_t index = 0; index < json.size(); ++index) {
        const char current = json[index];
        if (in_string) {
            if (escaped) escaped = false;
            else if (current == '\\') escaped = true;
            else if (current == '"') in_string = false;
            continue;
        }
        if (current == '"' && depth == 1) {
            const std::size_t begin = ++index;
            while (index < json.size() && json[index] != '"') {
                if (json[index] == '\\') ++index;
                ++index;
            }
            if (index >= json.size()) return std::nullopt;
            const auto decoded = DecodeJsonString(json.substr(begin, index - begin));
            if (!decoded.has_value() || *decoded != expected_key) continue;
            std::size_t value = index + 1;
            while (value < json.size() && std::isspace(static_cast<unsigned char>(json[value])) != 0) ++value;
            if (value >= json.size() || json[value] != ':') continue;
            while (++value < json.size() && std::isspace(static_cast<unsigned char>(json[value])) != 0) {}
            if (value >= json.size() || json[value] != '{') return std::nullopt;
            const std::size_t object_begin = value;
            std::int32_t object_depth = 0;
            bool value_string = false;
            bool value_escaped = false;
            for (; value < json.size(); ++value) {
                const char character = json[value];
                if (value_string) {
                    if (value_escaped) value_escaped = false;
                    else if (character == '\\') value_escaped = true;
                    else if (character == '"') value_string = false;
                    continue;
                }
                if (character == '"') value_string = true;
                else if (character == '{') ++object_depth;
                else if (character == '}' && --object_depth == 0) {
                    return json.substr(object_begin, value - object_begin + 1);
                }
            }
            return std::nullopt;
        }
        if (current == '"') in_string = true;
        else if (current == '{') ++depth;
        else if (current == '}') --depth;
    }
    return std::nullopt;
}

bool ApplySfxConfig(LoaderRuntimeContext& context, const IndexedModFile& file,
                    void* canonical, ConfigPipelineStats* stats) {
    const auto contents = ReadIndexedFile(file, kMaximumConfigFileSize);
    if (!contents.has_value()) return false;
    const auto temporary = context.json.DeserializeInto(canonical, *contents);
    if (!temporary.has_value() || !SameManagedClass(context.runtime, canonical, *temporary)) return false;
    const auto canonical_class = context.runtime.ObjectClass(canonical);
    if (!canonical_class.has_value()) return false;
    const std::vector<std::string> names = DiscoverTopLevelStringKeys(*contents);
    const std::vector<Il2CppRuntime::InstanceField> fields = context.runtime.InstanceFields(*canonical_class);
    std::vector<GcHandle> handles;
    if (names.empty() || !Retain(context.runtime, *temporary, &handles)) return false;

    struct Entry { ManagedIntDictionary* dictionary; std::int32_t key; bool existed; void* previous; };
    std::vector<std::unique_ptr<ManagedIntDictionary>> dictionaries;
    std::vector<Entry> journal;
    for (const std::string& name : names) {
        const auto object_json = FindTopLevelObjectValue(*contents, name);
        const auto field = std::find_if(fields.begin(), fields.end(), [&](const auto& candidate) {
            return candidate.name == "<" + name + ">k__BackingField" && candidate.is_reference;
        });
        if (!object_json.has_value() || field == fields.end()) goto rollback;
        void* target = *reinterpret_cast<void**>(reinterpret_cast<std::byte*>(canonical) + field->offset);
        void* source = *reinterpret_cast<void**>(reinterpret_cast<std::byte*>(*temporary) + field->offset);
        if (target == nullptr || source == nullptr) goto rollback;
        auto target_dictionary = std::make_unique<ManagedIntDictionary>(context.runtime);
        ManagedIntDictionary source_dictionary(context.runtime);
        if (!target_dictionary->Probe(target) || !source_dictionary.Probe(source)) goto rollback;
        const bool allow_new = name == "armageddon_music_loop";
        for (const std::int32_t key : DiscoverTopLevelIntegerKeys(*object_json)) {
            bool exists = false;
            const auto replacement = source_dictionary.Get(key);
            if (!target_dictionary->TryContains(key, &exists) || !replacement.has_value() ||
                (!exists && !allow_new) || !Retain(context.runtime, *replacement, &handles)) goto rollback;
            void* previous = nullptr;
            if (exists) {
                const auto current = target_dictionary->Get(key);
                if (!current.has_value() || !Retain(context.runtime, *current, &handles)) goto rollback;
                previous = *current;
            }
            if (!target_dictionary->Set(key, *replacement) ||
                !target_dictionary->Get(key).has_value() ||
                *target_dictionary->Get(key) != *replacement) goto rollback;
            journal.push_back({target_dictionary.get(), key, exists, previous});
        }
        dictionaries.push_back(std::move(target_dictionary));
    }
    Adopt(&handles);
    ++stats->single_object_committed;
    return true;

rollback:
    bool restored = true;
    for (auto entry = journal.rbegin(); entry != journal.rend(); ++entry) {
        if (entry->existed) {
            restored = entry->dictionary->Set(entry->key, entry->previous) && restored;
        } else {
            restored = entry->dictionary->Remove(entry->key) && restored;
        }
    }
    if (!restored) stats->rollback_failed = true;
    return false;
}

bool ApplyDirectory(LoaderRuntimeContext& context, const IndexedMod& mod,
                    const DirectoryConfig& spec, void* canonical,
                    ConfigPipelineStats* stats) {
    const std::vector<const IndexedModFile*> files = FindDirectoryFiles(mod, spec.directory);
    if (files.empty()) {
        return true;
    }
    std::vector<GcHandle> handles;
    if (spec.key_kind == ConfigKeyKind::kInteger) {
        std::vector<std::pair<std::int32_t, void*>> entries;
        for (const IndexedModFile* file : files) {
            const auto key = ParseIntegerFileKey(*file);
            const auto contents = ReadIndexedFile(*file, kMaximumConfigFileSize);
            const auto node = contents.has_value() ? context.json.DeserializeNode(
                context.common_image, spec.handler_name, *contents) : std::nullopt;
            if (!key.has_value() || !node.has_value() ||
                !ValidateIdField(context, *node, *key) ||
                !Retain(context.runtime, *node, &handles)) {
                return false;
            }
            entries.emplace_back(*key, *node);
        }
        return ApplyIntEntries(context, canonical, entries, nullptr, &handles, stats);
    }

    std::vector<std::pair<std::string, void*>> entries;
    for (const IndexedModFile* file : files) {
        const auto key = ParseStringFileKey(*file);
        const auto contents = ReadIndexedFile(*file, kMaximumConfigFileSize);
        const auto node = contents.has_value() ? context.json.DeserializeNode(
            context.common_image, spec.handler_name, *contents) : std::nullopt;
        if (!key.has_value() || !node.has_value() ||
            !Retain(context.runtime, *node, &handles)) {
            return false;
        }
        entries.emplace_back(*key, *node);
    }
    return ApplyStringEntries(context, canonical, entries, &handles, stats);
}

bool ApplyRiteOrEvent(LoaderRuntimeContext& context, const IndexedMod& mod,
                      std::string_view directory, std::string_view dictionary_name,
                      std::string_view handler_name, std::string_view class_name,
                      std::string_view getter_name, ConfigPipelineStats* stats) {
    const auto canonical = context.Dictionary(dictionary_name);
    const auto datapool_class = context.runtime.ObjectClass(context.datapool);
    const auto getter = datapool_class.has_value() ?
        context.runtime.FindMethod(*datapool_class, getter_name, 1) : std::nullopt;
    const std::vector<const IndexedModFile*> files = FindDirectoryFiles(mod, directory);
    if (files.empty()) {
        return true;
    }
    const std::string kind(directory);
    const auto log_failure = [&](const IndexedModFile* file, std::string_view phase,
                                 std::int32_t id = 0) {
        std::string message = "node_prepare kind=" + kind + " mod=" + mod.name +
            " phase=" + std::string(phase);
        if (file != nullptr) {
            message += " file=" + file->relative_path;
        }
        if (id != 0) {
            message += " id=" + std::to_string(id);
        }
        LogMessage(message.c_str());
    };
    if (!canonical.has_value() || !getter.has_value()) {
        log_failure(nullptr, "canonical_unavailable");
        return false;
    }

    NodePostProcessor post_processor(context);
    if (!post_processor.Probe()) {
        log_failure(nullptr, "post_process_probe_failed");
        return false;
    }

    std::vector<GcHandle> handles;
    std::vector<std::pair<std::int32_t, void*>> entries;
    std::size_t logged_successes = 0;
    for (const IndexedModFile* file : files) {
        const auto key = ParseIntegerFileKey(*file);
        const auto contents = ReadIndexedFile(*file, kMaximumConfigFileSize);
        const auto node = contents.has_value() ? context.json.DeserializeNode(
            context.common_image, handler_name, *contents) : std::nullopt;
        if (!key.has_value()) {
            log_failure(file, "invalid_file_key");
            return false;
        }
        if (!node.has_value()) {
            log_failure(file, "deserialize_failed", *key);
            return false;
        }
        if (!ValidateClass(context, *node, class_name)) {
            log_failure(file, "class_validation_failed", *key);
            return false;
        }
        if (!ValidateIdField(context, *node, *key)) {
            log_failure(file, "id_validation_failed", *key);
            return false;
        }
        const NodePrepareStats prepared = directory == "rite" ?
            post_processor.PrepareRite(*node, &handles) :
            post_processor.PrepareEvent(*node, &handles);
        if (!prepared.ok()) {
            log_failure(file, PrepareStageName(prepared.stage), *key);
            return false;
        }
        if (!Retain(context.runtime, *node, &handles)) {
            log_failure(file, "retain_failed", *key);
            return false;
        }
        if (logged_successes < 8) {
            char message[384]{};
            if (directory == "rite") {
                std::snprintf(message, sizeof(message),
                              "node_prepare kind=rite mod=%s file=%s id=%d result=ok "
                              "settlements=%zu/%zu/%zu slots=%zu tags=%zu",
                              mod.name.c_str(), file->relative_path.c_str(), *key,
                              prepared.primary_settlements, prepared.secondary_settlements,
                              prepared.tertiary_settlements, prepared.filled_slot_icons,
                              prepared.translated_tags);
            } else {
                std::snprintf(message, sizeof(message),
                              "node_prepare kind=event mod=%s file=%s id=%d result=ok "
                              "timings=%zu normal=%zu cached=%zu rite_settlement=%s",
                              mod.name.c_str(), file->relative_path.c_str(), *key,
                              prepared.timings, prepared.normal_settlements,
                              prepared.cached_settlements,
                              prepared.has_rite_settlement ? "yes" : "no");
            }
            LogMessage(message);
            ++logged_successes;
        }
        entries.emplace_back(*key, *node);
    }
    if (files.size() > logged_successes) {
        const std::string message = "node_prepare kind=" + kind + " mod=" + mod.name +
            " suppressed=" + std::to_string(files.size() - logged_successes);
        LogMessage(message.c_str());
    }
    if (!ApplyIntEntries(context, *canonical, entries, *getter, &handles, stats)) {
        log_failure(nullptr, "transaction_failed");
        return false;
    }
    if (directory == "rite") {
        for (const auto& [id, node] : entries) {
            static_cast<void>(node);
            stats->rite_ids_committed.push_back(id);
        }
    }
    const std::string message = "canonical_batch kind=" + kind + " mod=" + mod.name +
        " entries=" + std::to_string(entries.size()) + " result=committed";
    LogMessage(message.c_str());
    return true;
}

}  // namespace

LoaderRuntimeContext::LoaderRuntimeContext(const Il2CppApi& value)
    : api(value), runtime(value), json(runtime) {}

bool LoaderRuntimeContext::Resolve() {
    const auto core = runtime.FindImage({"Core.dll", "Il2CppCore.dll"});
    const auto common = runtime.FindImage({"Common.dll", "Il2CppCommon.dll"});
    if (!core.has_value() || !common.has_value() || !json.Probe()) {
        return false;
    }
    core_image = *core;
    common_image = *common;
    const auto application = runtime.FindClass(core_image, {"", "Il2Cpp"}, "GameApplication");
    if (!application.has_value()) {
        return false;
    }
    const auto pool = runtime.StaticFieldValue(*application, "datapool");
    if (!pool.has_value()) {
        return false;
    }
    datapool = *pool;
    const auto config_value = runtime.InstanceFieldValue(datapool, "<config>k__BackingField");
    if (!config_value.has_value()) {
        return false;
    }
    config = *config_value;
    return true;
}

std::optional<void*> LoaderRuntimeContext::Dictionary(std::string_view name) const {
    return runtime.InstanceFieldValue(config, "<" + std::string(name) + ">k__BackingField");
}

ConfigPipeline::ConfigPipeline(LoaderRuntimeContext& context, const ModFileIndex& index)
    : context_(context), index_(index) {}

ConfigPipelineStats ConfigPipeline::Apply(ConfigPipelineStage stage) {
    ConfigPipelineStats stats;

    if (stage == ConfigPipelineStage::kUpgrade) {
        const auto upgrades = context_.Dictionary("upgrade");
        if (!upgrades.has_value()) {
            return stats;
        }
        for (const IndexedMod& mod : index_.mods) {
            const IndexedModFile* file = FindFile(mod, "config/upgrade.json");
            if (file == nullptr) {
                continue;
            }
            ++stats.upgrade_mods;
            const auto contents = ReadIndexedFile(*file, kMaximumConfigFileSize);
            if (contents.has_value() &&
                ApplyIntSingleFile(context_, *file, *upgrades, "UpgradeNode", &stats)) {
                ++stats.upgrade_committed;
                stats.upgrade_entries += DiscoverTopLevelIntegerKeys(*contents).size();
            } else {
                ++stats.failed_transactions;
                if (stats.rollback_failed) {
                    return stats;
                }
            }
        }
        return stats;
    }

    if (stage == ConfigPipelineStage::kRite ||
        stage == ConfigPipelineStage::kEvent) {
        const bool rite = stage == ConfigPipelineStage::kRite;
        const std::string_view directory = rite ? "rite" : "event";
        for (const IndexedMod& mod : index_.mods) {
            if (FindDirectoryFiles(mod, directory).empty()) {
                continue;
            }
            const bool applied = rite ?
                ApplyRiteOrEvent(context_, mod, "rite", "rite", "RiteNode_JsonHandler",
                                 "RiteNode", "GetRiteData", &stats) :
                ApplyRiteOrEvent(context_, mod, "event", "events", "EventNode_JsonHandler",
                                 "EventNode", "GetEventData", &stats);
            if (applied) {
                if (rite) {
                    ++stats.rite_committed;
                } else {
                    ++stats.event_committed;
                }
            } else {
                ++stats.failed_transactions;
            }
            if (stats.rollback_failed) {
                return stats;
            }
        }
        return stats;
    }

    for (const SingleFileConfig& spec : IntegerSingleFileConfigs()) {
        const auto canonical = context_.Dictionary(spec.dictionary_name);
        if (!canonical.has_value()) {
            continue;
        }
        for (const IndexedMod& mod : index_.mods) {
            const IndexedModFile* file = FindFile(
                mod, "config/" + std::string(spec.path));
            if (file == nullptr) {
                continue;
            }
            if (ApplyIntSingleFile(context_, *file, *canonical, {}, &stats)) {
                ++stats.single_file_committed;
            } else {
                ++stats.failed_transactions;
                if (stats.rollback_failed) {
                    return stats;
                }
            }
        }
    }

    for (const SingleFileConfig& spec : StringSingleFileConfigs()) {
        const auto canonical = context_.Dictionary(spec.dictionary_name);
        if (!canonical.has_value()) {
            continue;
        }
        for (const IndexedMod& mod : index_.mods) {
            const IndexedModFile* file = FindFile(
                mod, "config/" + std::string(spec.path));
            if (file == nullptr) {
                continue;
            }
            if (ApplyStringSingleFile(context_, *file, *canonical, &stats)) {
                ++stats.single_file_committed;
            } else {
                ++stats.failed_transactions;
                if (stats.rollback_failed) {
                    return stats;
                }
            }
        }
    }

    for (const SingleObjectConfig& spec : SingleObjectConfigs()) {
        const auto canonical = context_.Dictionary(spec.config_name);
        if (!canonical.has_value()) {
            continue;
        }
        for (const IndexedMod& mod : index_.mods) {
            const IndexedModFile* file = FindFile(
                mod, "config/" + std::string(spec.path));
            if (file == nullptr) {
                continue;
            }
            const bool applied = spec.merge_policy == SingleObjectMergePolicy::kExistingKeysOnly ?
                ApplySfxConfig(context_, *file, *canonical, &stats) :
                ApplySingleObject(context_, *file, spec, *canonical, &stats);
            if (!applied) {
                ++stats.failed_transactions;
                if (stats.rollback_failed) {
                    return stats;
                }
            }
        }
    }

    for (const DirectoryConfig& spec : DirectoryConfigs()) {
        const auto canonical = context_.Dictionary(spec.dictionary_name);
        if (!canonical.has_value()) {
            continue;
        }
        for (const IndexedMod& mod : index_.mods) {
            if (FindDirectoryFiles(mod, spec.directory).empty()) {
                continue;
            }
            if (ApplyDirectory(context_, mod, spec, *canonical, &stats)) {
                ++stats.directory_committed;
            } else {
                ++stats.failed_transactions;
                if (stats.rollback_failed) {
                    return stats;
                }
            }
        }
    }
    return stats;
}

}  // namespace modloader
