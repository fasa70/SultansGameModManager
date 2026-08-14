#pragma once
#include <string>
#include <vector>

namespace sultan {

bool smart_allow_deletion(
    const std::vector<std::string>& field_path,
    bool is_array_element);

}  // namespace sultan
