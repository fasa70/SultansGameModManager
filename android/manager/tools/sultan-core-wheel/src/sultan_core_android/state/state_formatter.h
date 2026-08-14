#pragma once
#include "change_kind.h"
#include <cstdint>
#include <string>
#include <vector>

namespace sultan {

using std::string;
using std::vector;

struct StateBase;

struct FormatResult {
    vector<string> left_lines;
    vector<string> right_lines;
    vector<int8_t> left_kinds;   // -1 = 填充行, 0-15 = ChangeKind
    vector<int8_t> right_kinds;

    size_t size() const { return left_lines.size(); }
};

FormatResult format_state(const StateBase& root, int highlight_version);

}  // namespace sultan
