#pragma once
#include <string>
#include <vector>

namespace sultan {

struct RepairEntry {
    size_t line;
    std::string desc;
};

struct CleanResult {
    std::string text;
    std::vector<RepairEntry> repairs;
};

CleanResult clean_text(const std::string& text);

}  // namespace sultan
