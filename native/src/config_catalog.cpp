#include "modloader/config_catalog.h"

#include <algorithm>
#include <cctype>
#include <limits>
#include <optional>
#include <unordered_set>

namespace modloader {
namespace {

const std::vector<SingleFileConfig> kIntegerSingleFileConfigs = {
    {"over.json", "over", ConfigKeyKind::kInteger},
    {"quest.json", "quest", ConfigKeyKind::kInteger},
    {"rite_template_mappings.json", "rite_template_mapping", ConfigKeyKind::kInteger},
    {"over_music_config.json", "over_music_config", ConfigKeyKind::kInteger},
    {"sfx_npc_role_dub.json", "sfx_npc_role_dub", ConfigKeyKind::kInteger},
    {"sfx_settle_card_new.json", "sfx_settle_card_new", ConfigKeyKind::kInteger},
    {"gallery_cards.json", "gallery_cards", ConfigKeyKind::kInteger},
    {"gallery_cg.json", "gallery_cg", ConfigKeyKind::kInteger},
    {"achievement.json", "gallery_achievement", ConfigKeyKind::kInteger},
};

const std::vector<SingleFileConfig> kStringSingleFileConfigs = {
    {"ui.json", "lang", ConfigKeyKind::kString},
    {"tag.json", "tag", ConfigKeyKind::kString},
    {"textstyle.json", "textstyle", ConfigKeyKind::kString},
    {"imagestyle.json", "imagestyle", ConfigKeyKind::kString},
    {"mobile_help.json", "mobile_help", ConfigKeyKind::kString},
};

const std::vector<DirectoryConfig> kDirectoryConfigs = {
    {"init", "init", "InitNode_JsonHandler", ConfigKeyKind::kInteger},
    {"loot", "loot", "LootNode_JsonHandler", ConfigKeyKind::kInteger},
    {"after_story", "after_story", "AfterStoryNode_JsonHandler", ConfigKeyKind::kInteger},
    {"rite_template", "rite_template", "RiteTemplateNode_JsonHandler", ConfigKeyKind::kInteger},
    {"dt", "dt", "DialogTreeNode_JsonHandler", ConfigKeyKind::kString},
    {"wizard", "wizard", "WizardNode_JsonHandler", ConfigKeyKind::kString},
};

const std::vector<SingleObjectConfig> kSingleObjectConfigs = {
    {"variable.json", "variable", SingleObjectMergePolicy::kReplaceFields},
    {"credits.json", "credits", SingleObjectMergePolicy::kReplaceFields},
    {"sfx_config.json", "sfx_config", SingleObjectMergePolicy::kExistingKeysOnly},
};

}  // namespace

namespace {

std::optional<unsigned int> HexDigit(char value) {
    if (value >= '0' && value <= '9') {
        return static_cast<unsigned int>(value - '0');
    }
    if (value >= 'a' && value <= 'f') {
        return static_cast<unsigned int>(value - 'a' + 10);
    }
    if (value >= 'A' && value <= 'F') {
        return static_cast<unsigned int>(value - 'A' + 10);
    }
    return std::nullopt;
}

void AppendUtf8(std::string* output, unsigned int codepoint) {
    if (codepoint <= 0x7fU) {
        output->push_back(static_cast<char>(codepoint));
    } else if (codepoint <= 0x7ffU) {
        output->push_back(static_cast<char>(0xc0U | (codepoint >> 6U)));
        output->push_back(static_cast<char>(0x80U | (codepoint & 0x3fU)));
    } else {
        output->push_back(static_cast<char>(0xe0U | (codepoint >> 12U)));
        output->push_back(static_cast<char>(0x80U | ((codepoint >> 6U) & 0x3fU)));
        output->push_back(static_cast<char>(0x80U | (codepoint & 0x3fU)));
    }
}

}  // namespace

std::optional<std::string> DecodeJsonString(std::string_view value) {
    std::string decoded;
    for (std::size_t index = 0; index < value.size(); ++index) {
        const char current = value[index];
        if (current != '\\') {
            decoded.push_back(current);
            continue;
        }
        if (++index >= value.size()) {
            return std::nullopt;
        }
        switch (value[index]) {
            case '"': decoded.push_back('"'); break;
            case '\\': decoded.push_back('\\'); break;
            case '/': decoded.push_back('/'); break;
            case 'b': decoded.push_back('\b'); break;
            case 'f': decoded.push_back('\f'); break;
            case 'n': decoded.push_back('\n'); break;
            case 'r': decoded.push_back('\r'); break;
            case 't': decoded.push_back('\t'); break;
            case 'u': {
                if (index + 4 >= value.size()) {
                    return std::nullopt;
                }
                unsigned int codepoint = 0;
                for (std::size_t digit = 0; digit < 4; ++digit) {
                    const auto hex = HexDigit(value[index + 1 + digit]);
                    if (!hex.has_value()) {
                        return std::nullopt;
                    }
                    codepoint = codepoint * 16U + *hex;
                }
                index += 4;
                AppendUtf8(&decoded, codepoint);
                break;
            }
            default:
                return std::nullopt;
        }
    }
    return decoded;
}

const std::vector<SingleFileConfig>& IntegerSingleFileConfigs() {
    return kIntegerSingleFileConfigs;
}

const std::vector<SingleFileConfig>& StringSingleFileConfigs() {
    return kStringSingleFileConfigs;
}

const std::vector<DirectoryConfig>& DirectoryConfigs() {
    return kDirectoryConfigs;
}

const std::vector<SingleObjectConfig>& SingleObjectConfigs() {
    return kSingleObjectConfigs;
}

bool IsExplicitlyUnsupportedConfig(std::string_view) {
    return false;
}

std::vector<std::int32_t> DiscoverTopLevelIntegerKeys(std::string_view json) {
    std::vector<std::int32_t> keys;
    std::unordered_set<std::int32_t> seen;
    std::int32_t depth = 0;
    bool in_string = false;
    bool escaped = false;
    bool line_comment = false;
    bool block_comment = false;

    for (std::size_t index = 0; index < json.size(); ++index) {
        const char current = json[index];
        const char next = index + 1 < json.size() ? json[index + 1] : '\0';
        if (line_comment) {
            if (current == '\n' || current == '\r') {
                line_comment = false;
            }
            continue;
        }
        if (block_comment) {
            if (current == '*' && next == '/') {
                block_comment = false;
                ++index;
            }
            continue;
        }
        if (in_string) {
            if (escaped) {
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == '"') {
                in_string = false;
            }
            continue;
        }
        if (current == '/' && next == '/') {
            line_comment = true;
            ++index;
            continue;
        }
        if (current == '/' && next == '*') {
            block_comment = true;
            ++index;
            continue;
        }
        if (current == '"') {
            const std::size_t start = index + 1;
            std::size_t end = start;
            while (end < json.size() && json[end] >= '0' && json[end] <= '9') {
                ++end;
            }
            if (depth == 1 && end > start && end < json.size() && json[end] == '"') {
                std::size_t cursor = end + 1;
                while (cursor < json.size() &&
                       std::isspace(static_cast<unsigned char>(json[cursor])) != 0) {
                    ++cursor;
                }
                if (cursor < json.size() && json[cursor] == ':') {
                    std::int64_t value = 0;
                    bool valid = true;
                    for (std::size_t digit = start; digit < end; ++digit) {
                        value = value * 10 + (json[digit] - '0');
                        if (value > std::numeric_limits<std::int32_t>::max()) {
                            valid = false;
                            break;
                        }
                    }
                    const auto key = static_cast<std::int32_t>(value);
                    if (valid && seen.insert(key).second) {
                        keys.push_back(key);
                    }
                }
            }
            in_string = true;
            continue;
        }
        if (current == '{') {
            ++depth;
        } else if (current == '}') {
            --depth;
        }
    }
    return keys;
}

std::vector<std::string> DiscoverTopLevelStringKeys(std::string_view json) {
    std::vector<std::string> keys;
    std::unordered_set<std::string> seen;
    std::int32_t depth = 0;
    bool line_comment = false;
    bool block_comment = false;

    for (std::size_t index = 0; index < json.size(); ++index) {
        const char current = json[index];
        const char next = index + 1 < json.size() ? json[index + 1] : '\0';
        if (line_comment) {
            if (current == '\n' || current == '\r') {
                line_comment = false;
            }
            continue;
        }
        if (block_comment) {
            if (current == '*' && next == '/') {
                block_comment = false;
                ++index;
            }
            continue;
        }
        if (current == '/' && next == '/') {
            line_comment = true;
            ++index;
            continue;
        }
        if (current == '/' && next == '*') {
            block_comment = true;
            ++index;
            continue;
        }
        if (current == '"') {
            const std::size_t start = index + 1;
            std::size_t end = start;
            bool escaped = false;
            while (end < json.size()) {
                if (escaped) {
                    escaped = false;
                } else if (json[end] == '\\') {
                    escaped = true;
                } else if (json[end] == '"') {
                    break;
                }
                ++end;
            }
            if (end == json.size()) {
                break;
            }
            std::size_t cursor = end + 1;
            while (cursor < json.size() &&
                   std::isspace(static_cast<unsigned char>(json[cursor])) != 0) {
                ++cursor;
            }
            if (depth == 1 && cursor < json.size() && json[cursor] == ':') {
                const auto key = DecodeJsonString(json.substr(start, end - start));
                if (key.has_value() && seen.insert(*key).second) {
                    keys.push_back(*key);
                }
            }
            index = end;
            continue;
        }
        if (current == '{') {
            ++depth;
        } else if (current == '}') {
            --depth;
        }
    }
    return keys;
}

}  // namespace modloader
