#pragma once
#include "change_kind.h"
#include "delta_node.h"
#include <memory>
#include <vector>

namespace sultan {

class JsonDoc;

DeltaNodePtr compute_delta(
    const JsonDoc& base,
    const JsonDoc& mod,
    MergeMode merge_mode = MergeMode::Normal,
    bool skip_root_deletion = false);

bool remap_delta_to_current(
    DeltaDict& delta,
    const JsonDoc& hist_base,
    const JsonDoc& current_base);

struct FileGroupInput {
    MergeMode mode;
    const JsonDoc* mod_doc;
    const JsonDoc* hist_doc;
};

std::vector<std::unique_ptr<DeltaDict>> process_file_group(
    const JsonDoc& base_doc,
    const std::vector<FileGroupInput>& inputs);

std::vector<std::unique_ptr<DeltaDict>> batch_process_all_groups(
    const std::vector<const JsonDoc*>& base_docs,
    const std::vector<size_t>& group_offsets,
    const std::vector<FileGroupInput>& flat_inputs);

}  // namespace sultan
