#include "delta_rules.h"
#include <unordered_set>

namespace sultan {

static const std::unordered_set<std::string> ALLOW_DELETE_CONTEXTS = {
    "condition", "action", "result",
};

static const std::unordered_set<std::string> ALLOW_DELETE_FIELDS = {
    "result_title", "result_text",
};

bool smart_allow_deletion(
    const std::vector<std::string>& field_path,
    bool is_array_element)
{
    if (is_array_element)
        return false;

    for (const auto& segment : field_path) {
        if (ALLOW_DELETE_CONTEXTS.count(segment))
            return true;
    }

    if (!field_path.empty() && ALLOW_DELETE_FIELDS.count(field_path.back()))
        return true;

    return false;
}

}  // namespace sultan
