#pragma once
#include "delta_node.h"
#include "state_node.h"
#include <string>
#include <vector>

namespace sultan {

class JsonState;

void apply_dict_delta(
    JsonDictState& base,
    const DeltaDict& delta,
    std::vector<std::string>* field_path = nullptr,
    int version = 0,
    bool is_override = false);

void apply_array_delta(
    JsonArrayState& base,
    const DeltaArray& delta,
    std::vector<std::string>* field_path = nullptr,
    int version = 0,
    bool is_override = false);

StateNodePtr apply_field_delta(
    const DeltaElement& diff,
    JsonElementState* existing,
    int version,
    bool is_override);

void apply_delta_to_state(
    JsonState& state,
    const DeltaDict& delta,
    std::vector<std::string>* field_path = nullptr,
    int version = 0,
    bool is_override = false);

}  // namespace sultan
