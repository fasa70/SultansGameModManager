#include "compute_delta.h"
#include "apply_delta.h"
#include "array_match.h"
#include "delta_rules.h"
#include "json_doc.h"
#include "json_ops.h"
#include "json_state.h"
#include "json_val.h"
#include "perf.h"
#include "state_node.h"
#include <algorithm>
#include <cassert>
#include <cstring>
#include <string_view>
#include <unordered_map>

namespace sultan {

using std::string;
using std::vector;
using std::unordered_map;
using std::make_unique;


// ── 深度相等 ──

static bool deep_equal(JsonVal a, JsonVal b);

static bool obj_equal(JsonVal a, JsonVal b) {
    size_t n = a.obj_size();
    if (n != b.obj_size()) return false;

    struct KVEntry { std::string_view key; JsonVal val; };
    vector<KVEntry> avec, bvec;
    avec.reserve(n);
    bvec.reserve(n);

    {
        auto it = a.obj_iter();
        JsonVal::ObjEntry e;
        while (it.next(e))
            avec.push_back({std::string_view(e.key, e.key_len), e.val});
    }
    {
        auto it = b.obj_iter();
        JsonVal::ObjEntry e;
        while (it.next(e))
            bvec.push_back({std::string_view(e.key, e.key_len), e.val});
    }

    auto cmp = [](const KVEntry& x, const KVEntry& y) { return x.key < y.key; };
    std::sort(avec.begin(), avec.end(), cmp);
    std::sort(bvec.begin(), bvec.end(), cmp);

    for (size_t i = 0; i < n; ++i) {
        if (avec[i].key != bvec[i].key) return false;
        if (!deep_equal(avec[i].val, bvec[i].val)) return false;
    }
    return true;
}

static bool arr_equal(JsonVal a, JsonVal b) {
    if (a.arr_size() != b.arr_size()) return false;
    auto ait = a.arr_iter();
    auto bit = b.arr_iter();
    JsonVal ae, be;
    while (ait.next(ae)) {
        if (!bit.next(be)) return false;
        if (!deep_equal(ae, be)) return false;
    }
    return true;
}

static bool deep_equal(JsonVal a, JsonVal b) {
    if (a.type() != b.type()) return false;
    switch (a.type()) {
        case JsonType::Null: return true;
        case JsonType::Bool: return a.get_bool() == b.get_bool();
        case JsonType::Int:  return a.get_int() == b.get_int();
        case JsonType::Real: return a.get_real() == b.get_real();
        case JsonType::Str:  return std::strcmp(a.get_str(), b.get_str()) == 0;
        case JsonType::Obj:  return obj_equal(a, b);
        case JsonType::Arr:  return arr_equal(a, b);
    }
    return false;
}

// ── 重复键收集 ──

struct ObjKeys {
    struct KV { vector<JsonVal> vals; };
    vector<string> key_order;
    unordered_map<string, KV> entries;
    bool has_dup = false;

    static ObjKeys collect(JsonVal obj) {
        ObjKeys r;
        auto it = obj.obj_iter();
        JsonVal::ObjEntry e;
        while (it.next(e)) {
            string k(e.key, e.key_len);
            auto& kv = r.entries[k];
            if (kv.vals.empty()) r.key_order.push_back(k);
            else r.has_dup = true;
            kv.vals.push_back(e.val);
        }
        return r;
    }
};

// ── order 列表构建 ──

static vector<int> build_order(
    const ArrayMatching& matching,
    const unordered_map<int, int>& added_id_map)
{
    unordered_map<int, int> paired_map;
    for (auto& [base_idx, mod_idx] : matching.pairs)
        paired_map[mod_idx] = base_idx + 1;

    int mod_max = -1;
    for (auto& [bi, mi] : matching.pairs)
        if (mi > mod_max) mod_max = mi;
    for (int mi : matching.unmatched_mod)
        if (mi > mod_max) mod_max = mi;
    int total_mod = mod_max + 1;

    vector<int> order = {0};
    for (int mi = 0; mi < total_mod; ++mi) {
        auto pit = paired_map.find(mi);
        if (pit != paired_map.end()) {
            order.push_back(pit->second);
        } else {
            auto ait = added_id_map.find(mi);
            if (ait != added_id_map.end())
                order.push_back(ait->second);
        }
    }
    order.push_back(-1);
    return order;
}

// ── delta 计算（前向声明） ──

static DeltaNodePtr recursive_delta(
    JsonVal base, JsonVal mod,
    vector<string>* field_path,
    MergeMode merge_mode,
    bool skip_deletion = false);

static DeltaNodePtr dict_delta(
    JsonVal base_obj, JsonVal mod_obj,
    vector<string>* field_path,
    MergeMode merge_mode,
    bool skip_deletion);

static DeltaNodePtr array_delta(
    const vector<JsonVal>& base_elems,
    const vector<JsonVal>& mod_elems,
    vector<string>* field_path,
    bool is_duplist,
    MergeMode merge_mode,
    bool skip_deletion);

static DeltaNodePtr array_delta(
    const vector<JsonVal>& base_elems,
    const vector<JsonVal>& mod_elems,
    vector<string>* field_path,
    bool is_duplist,
    MergeMode merge_mode,
    bool skip_deletion)
{
    auto matching = match_by_heuristic(base_elems, mod_elems);
    int base_count = static_cast<int>(base_elems.size());

    vector<DeltaNodePtr> diffs;
    vector<int> indices;
    int next_added_id = base_count + 1;

    for (auto& [base_idx, mod_idx] : matching.pairs) {
        auto elem_delta = recursive_delta(
            base_elems[base_idx], mod_elems[mod_idx], field_path, merge_mode, skip_deletion);
        if (elem_delta) {
            diffs.push_back(std::move(elem_delta));
            indices.push_back(base_idx + 1);
        }
    }

    unordered_map<int, int> added_id_map;
    for (int mod_idx : matching.unmatched_mod) {
        diffs.push_back(make_delta_element(ChangeKind::Added, mod_elems[mod_idx]));
        indices.push_back(next_added_id);
        added_id_map[mod_idx] = next_added_id;
        next_added_id++;
    }

    if (merge_mode != MergeMode::Smart && !skip_deletion) {
        for (int base_idx : matching.unmatched_base) {
            diffs.push_back(make_delta_element(ChangeKind::Deleted, base_elems[base_idx]));
            indices.push_back(base_idx + 1);
        }
    }

    if (diffs.empty())
        return nullptr;

    auto order = build_order(matching, added_id_map);
    auto arr = make_unique<DeltaArray>();
    arr->diffs = std::move(diffs);
    arr->base_count = base_count;
    arr->indices = std::move(indices);
    arr->order = std::move(order);
    arr->is_duplist = is_duplist;
    return arr;
}

// ── dict_delta：对象级 delta ──

static DeltaNodePtr dict_delta(
    JsonVal base_obj, JsonVal mod_obj,
    vector<string>* field_path,
    MergeMode merge_mode,
    bool skip_deletion)
{
    auto base_keys = ObjKeys::collect(base_obj);
    auto mod_keys = ObjKeys::collect(mod_obj);

    // 构建去重 key 集合（保序：mod key_order 在前，base 独有的在后）
    vector<string> all_keys = mod_keys.key_order;
    for (auto& key : base_keys.key_order) {
        if (!mod_keys.entries.count(key))
            all_keys.push_back(key);
    }

    static const vector<JsonVal> empty_vals;
    auto dict = make_unique<DeltaDict>();

    for (auto& key : all_keys) {
        auto bit = base_keys.entries.find(key);
        auto mit = mod_keys.entries.find(key);
        const auto& base_vals = (bit != base_keys.entries.end()) ? bit->second.vals : empty_vals;
        const auto& mod_vals = (mit != mod_keys.entries.end()) ? mit->second.vals : empty_vals;
        bool in_base = !base_vals.empty();
        bool in_mod = !mod_vals.empty();

        if (!in_mod) {
            if (skip_deletion) continue;
            if (merge_mode == MergeMode::Smart) {
                if (field_path) field_path->push_back(key);
                bool allow = !field_path || smart_allow_deletion(*field_path, false);
                if (field_path) field_path->pop_back();
                if (!allow) continue;
            }
        }

        if (base_vals.size() > 1 || mod_vals.size() > 1) {
            auto sub = array_delta(base_vals, mod_vals, field_path, true, merge_mode, skip_deletion);
            if (sub) dict->insert(key, std::move(sub));
        } else if (!in_base) {
            dict->insert(key, make_delta_element(ChangeKind::Added, mod_vals[0]));
        } else if (!in_mod) {
            dict->insert(key, make_delta_element(ChangeKind::Deleted, base_vals[0]));
        } else {
            if (field_path) field_path->push_back(key);
            auto sub = recursive_delta(base_vals[0], mod_vals[0], field_path, merge_mode, skip_deletion);
            if (field_path) field_path->pop_back();
            if (sub) dict->insert(key, std::move(sub));
        }
    }

    if (dict->empty()) return nullptr;
    return dict;
}

// ── recursive_delta：类型分发 ──

static DeltaNodePtr recursive_delta(
    JsonVal base, JsonVal mod,
    vector<string>* field_path,
    MergeMode merge_mode,
    bool skip_deletion)
{
    SULTAN_PERF_SCOPE("recursive_delta");
    if (deep_equal(base, mod))
        return nullptr;

    if (base.is_obj() && mod.is_obj())
        return dict_delta(base, mod, field_path, merge_mode, skip_deletion);

    // array vs array（含标量↔数组归一化）
    bool go_array = (base.is_arr() && mod.is_arr())
        || (!base.is_obj() && !base.is_arr() && mod.is_arr())
        || (base.is_arr() && !mod.is_obj() && !mod.is_arr());

    if (go_array) {
        auto base_elems = base.is_arr() ? collect_arr(base) : vector<JsonVal>{base};
        auto mod_elems = mod.is_arr() ? collect_arr(mod) : vector<JsonVal>{mod};
        return array_delta(base_elems, mod_elems, field_path, false, merge_mode, skip_deletion);
    }

    // 标量变化
    return make_delta_element(ChangeKind::Changed, mod);
}

// ── 公开 API ──

DeltaNodePtr compute_delta(
    const JsonDoc& base,
    const JsonDoc& mod,
    MergeMode merge_mode,
    bool skip_root_deletion)
{
    SULTAN_PERF_SCOPE("compute_delta");
    if (!base.valid() || !mod.valid())
        return nullptr;
    vector<string> root_path;
    vector<string>* fp = (merge_mode == MergeMode::Smart) ? &root_path : nullptr;
    return recursive_delta(base.root(), mod.root(), fp, merge_mode, skip_root_deletion);
}

// ── remap 前置声明 ──

static bool remap_dict_diff(
    DeltaDict& delta, JsonVal hist_obj, JsonVal cur_obj);
static bool remap_array_diff(
    DeltaArray& delta, const vector<JsonVal>& hist_elems, const vector<JsonVal>& cur_elems);
static bool remap_array_diff(
    DeltaArray& delta, JsonVal hist_arr, JsonVal cur_arr);

// ── remap: 标量/叶子节点重映射 ──
// 返回 true=保留, false=删除; node 可能被替换

static bool remap_field_diff(
    DeltaNodePtr& node, bool cur_has, JsonVal cur_val)
{
    auto& entry = node->as_element();
    ChangeKind bk = sultan::base_kind(entry.kind_);

    if (bk == ChangeKind::Deleted) {
        if (!cur_has) return false;
        entry.value = cur_val;
        return true;
    }

    if (bk == ChangeKind::Added) {
        if (!cur_has) return true;

        if (entry.value.is_obj() || entry.value.is_arr()) {
            if (deep_equal(cur_val, entry.value)) return false;
            node = recursive_delta(cur_val, entry.value, nullptr, MergeMode::Normal);
            return node != nullptr;
        }
        if (deep_equal(cur_val, entry.value)) return false;
        entry.kind_ = ChangeKind::Changed;
        return true;
    }

    if (bk == ChangeKind::Changed) {
        if (!cur_has) { entry.kind_ = ChangeKind::Added; return true; }
        if (deep_equal(cur_val, entry.value)) return false;
        return true;
    }

    return true;
}

// ── remap: 数组 delta 重映射（核心 vector 重载） ──

static bool remap_array_diff(
    DeltaArray& delta,
    const vector<JsonVal>& hist_elems,
    const vector<JsonVal>& cur_elems);

// ── remap: 统一递归分发 ──

static bool remap_node(DeltaNodePtr& d, JsonVal hv, JsonVal cv) {
    switch (d->type()) {
        case DeltaType::Element:
            return remap_field_diff(d, cv.valid(), cv);
        case DeltaType::Dict:
            if (!hv.is_obj() || !cv.is_obj()) return false;
            return remap_dict_diff(d->as_dict(), hv, cv)
                && !d->as_dict().empty();
        case DeltaType::Array: {
            auto to_elems = [](JsonVal v) -> vector<JsonVal> {
                if (!v.valid()) return {};
                if (v.is_arr()) return collect_arr(v);
                return {v};
            };
            return remap_array_diff(d->as_array(), to_elems(hv), to_elems(cv));
        }
    }
    return false;
}

// ── remap: 数组 delta 重映射（JsonVal 委托） ──

static bool remap_array_diff(
    DeltaArray& delta, JsonVal hist_arr, JsonVal cur_arr)
{
    return remap_array_diff(delta, collect_arr(hist_arr), collect_arr(cur_arr));
}

// ── remap: 数组 delta 重映射（索引体系 hist→current） ──

static bool remap_array_diff(
    DeltaArray& delta,
    const vector<JsonVal>& hist_elems,
    const vector<JsonVal>& cur_elems)
{
    int hist_base_count = delta.base_count;
    int cur_base_count = static_cast<int>(cur_elems.size());

    auto matching = match_by_heuristic(hist_elems, cur_elems);

    unordered_map<int, int> hist_to_cur;
    for (auto& [hi, ci] : matching.pairs)
        hist_to_cur[hi] = ci;

    vector<DeltaNodePtr> new_diffs;
    vector<int> new_indices;
    unordered_map<int, int> id_remap;
    int added_counter = 0;

    auto accept = [&](DeltaNodePtr& d, int eid, int new_id) {
        new_diffs.push_back(std::move(d));
        new_indices.push_back(new_id);
        id_remap[eid] = new_id;
    };

    for (size_t i = 0; i < delta.diffs.size(); ++i) {
        int eid = delta.indices[i];
        auto& d = delta.diffs[i];

        if (eid > hist_base_count) {
            added_counter++;
            accept(d, eid, cur_base_count + added_counter);
            continue;
        }

        int hist_idx = eid - 1;
        auto hit = hist_to_cur.find(hist_idx);
        if (hit == hist_to_cur.end()) continue;

        int cur_idx = hit->second;
        int new_id = cur_idx + 1;

        JsonVal hv = hist_idx < (int)hist_elems.size() ? hist_elems[hist_idx] : JsonVal{};
        JsonVal cv = cur_idx  < (int)cur_elems.size()  ? cur_elems[cur_idx]  : JsonVal{};

        if (remap_node(d, hv, cv))
            accept(d, eid, new_id);
    }

    if (new_diffs.empty()) {
        delta.diffs.clear();
        delta.indices.clear();
        delta.order.clear();
        delta.base_count = cur_base_count;
        return false;
    }

    unordered_map<int, int> hist_id_to_cur_id;
    for (auto& [hi, ci] : matching.pairs)
        hist_id_to_cur_id[hi + 1] = ci + 1;

    vector<int> new_order;
    for (int eid : delta.order) {
        if (eid == 0 || eid == -1) {
            new_order.push_back(eid);
            continue;
        }
        if (eid > hist_base_count) {
            auto it2 = id_remap.find(eid);
            if (it2 != id_remap.end())
                new_order.push_back(it2->second);
        } else {
            auto it2 = hist_id_to_cur_id.find(eid);
            if (it2 != hist_id_to_cur_id.end())
                new_order.push_back(it2->second);
        }
    }

    delta.diffs = std::move(new_diffs);
    delta.base_count = cur_base_count;
    delta.indices = std::move(new_indices);
    delta.order = std::move(new_order);
    return true;
}

// ── remap: 字典 delta 重映射 ──

static bool remap_dict_diff(
    DeltaDict& delta, JsonVal hist_obj, JsonVal cur_obj)
{
    auto hist_keys = ObjKeys::collect(hist_obj);
    auto cur_keys = ObjKeys::collect(cur_obj);

    static const vector<JsonVal> empty_vals;
    vector<string> to_erase;

    for (auto& [key, diff] : delta.items) {
        auto hit = hist_keys.entries.find(key);
        auto cit = cur_keys.entries.find(key);
        const auto& hvals = (hit != hist_keys.entries.end()) ? hit->second.vals : empty_vals;
        const auto& cvals = (cit != cur_keys.entries.end()) ? cit->second.vals : empty_vals;

        bool keep;
        if (hvals.size() > 1 || cvals.size() > 1) {
            keep = remap_array_diff(diff->as_array(), hvals, cvals);
        } else {
            JsonVal hv = hvals.empty() ? JsonVal{} : hvals[0];
            JsonVal cv = cvals.empty() ? JsonVal{} : cvals[0];
            keep = remap_node(diff, hv, cv);
        }
        if (!keep) to_erase.push_back(key);
    }
    for (auto& k : to_erase)
        delta.items.erase(k);
    return !delta.empty();
}

// ── 公开 API: remap ──

bool remap_delta_to_current(
    DeltaDict& delta,
    const JsonDoc& hist_base,
    const JsonDoc& current_base)
{
    SULTAN_PERF_SCOPE("remap_delta");
    if (!hist_base.valid() || !current_base.valid())
        return false;
    JsonVal hist = hist_base.root();
    JsonVal cur = current_base.root();
    assert(hist.is_obj() && cur.is_obj());
    return remap_dict_diff(delta, hist, cur);
}

// ── process_file_group ──

std::vector<std::unique_ptr<DeltaDict>> process_file_group(
    const JsonDoc& base_doc,
    const std::vector<FileGroupInput>& inputs)
{
    SULTAN_PERF_SCOPE("process_file_group");
    bool is_dict = (classify_json(base_doc) == "dictionary");
    std::unique_ptr<JsonState> state;
    std::vector<std::unique_ptr<DeltaDict>> results;
    results.reserve(inputs.size());

    for (auto& inp : inputs) {
        DeltaNodePtr delta_node;

        if (inp.mode == MergeMode::Replace) {
            if (state) {
                JsonDoc cumulative = state->to_doc();
                delta_node = compute_delta(
                    cumulative, *inp.mod_doc, inp.mode, is_dict);
            } else {
                delta_node = compute_delta(
                    base_doc, *inp.mod_doc, inp.mode, is_dict);
            }
        } else if (inp.mode == MergeMode::Adaptive && inp.hist_doc) {
            delta_node = compute_delta(
                *inp.hist_doc, *inp.mod_doc, inp.mode, is_dict);
            if (delta_node && delta_node->type() == DeltaType::Dict) {
                if (!remap_delta_to_current(
                        delta_node->as_dict(), *inp.hist_doc, base_doc))
                    delta_node.reset();
            }
        } else {
            delta_node = compute_delta(
                base_doc, *inp.mod_doc, inp.mode, is_dict);
        }

        std::unique_ptr<DeltaDict> dd;
        if (delta_node && delta_node->type() == DeltaType::Dict)
            dd.reset(static_cast<DeltaDict*>(delta_node.release()));

        if (dd) {
            if (!state) {
                auto s = JsonState::from_doc(base_doc);
                state = make_unique<JsonState>(std::move(s));
            }
            apply_delta_to_state(*state, *dd, nullptr, 0, false);
        }

        results.push_back(std::move(dd));
    }
    return results;
}

std::vector<std::unique_ptr<DeltaDict>> batch_process_all_groups(
    const std::vector<const JsonDoc*>& base_docs,
    const std::vector<size_t>& group_offsets,
    const std::vector<FileGroupInput>& flat_inputs)
{
    SULTAN_PERF_SCOPE("batch_process_all_groups");
    std::vector<std::unique_ptr<DeltaDict>> all_results;
    all_results.reserve(flat_inputs.size());

    for (size_t g = 0; g < base_docs.size(); ++g) {
        size_t begin = group_offsets[g];
        size_t end = (g + 1 < group_offsets.size())
                     ? group_offsets[g + 1]
                     : flat_inputs.size();
        std::vector<FileGroupInput> group_inputs(
            flat_inputs.begin() + begin,
            flat_inputs.begin() + end);
        auto results = process_file_group(*base_docs[g], group_inputs);
        for (auto& r : results)
            all_results.push_back(std::move(r));
    }
    return all_results;
}

}  // namespace sultan
