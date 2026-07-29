#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace modloader {

enum class ConfigKeyKind {
    kInteger,
    kString,
};

struct SingleFileConfig {
    std::string_view path;
    std::string_view dictionary_name;
    ConfigKeyKind key_kind;
};

struct DirectoryConfig {
    std::string_view directory;
    std::string_view dictionary_name;
    std::string_view handler_name;
    ConfigKeyKind key_kind;
};

enum class SingleObjectMergePolicy {
    kReplaceFields,
    kExistingKeysOnly,
};

struct SingleObjectConfig {
    std::string_view path;
    std::string_view config_name;
    SingleObjectMergePolicy merge_policy;
};

const std::vector<SingleFileConfig>& IntegerSingleFileConfigs();
const std::vector<SingleFileConfig>& StringSingleFileConfigs();
const std::vector<DirectoryConfig>& DirectoryConfigs();
const std::vector<SingleObjectConfig>& SingleObjectConfigs();
bool IsExplicitlyUnsupportedConfig(std::string_view relative_path);
std::optional<std::string> DecodeJsonString(std::string_view value);
std::vector<std::int32_t> DiscoverTopLevelIntegerKeys(std::string_view json);
std::vector<std::string> DiscoverTopLevelStringKeys(std::string_view json);

}  // namespace modloader
