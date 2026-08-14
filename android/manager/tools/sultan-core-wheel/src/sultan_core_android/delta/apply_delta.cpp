#include "apply_delta.h"
#include "json_state.h"
#include "diag.h"
#include "perf.h"
#include <algorithm>
#include <stdexcept>
#include <unordered_map>
#include <unordered_set>

namespace sultan {

using std::make_unique;
using std::string;
using std::vector;
using std::unordered_map;
using std::unordered_set;

// ── apply_field_delta ──

StateNodePtr apply_field_delta(
    const DeltaElement& diff,
    JsonElementState* existing,
    int version,
    bool is_override)
{
    ChangeKind bk = sultan::base_kind(diff.kind_);

    ChangeKind modifier = ChangeKind::Origin;
    if (is_override) modifier |= ChangeKind::Override;

    JsonVal old_val;
    if (existing) {
        old_val = existing->value;
        if (existing->is_modified() && existing->version != version)
            modifier |= ChangeKind::MultiMod;
        modifier |= change_flags(existing->kind_);
    }

    ChangeKind kind = bk | modifier;

    StateNodePtr holder;
    JsonElementState* target;
    if (existing) {
        target = existing;
    } else {
        holder = make_unique<JsonElementState>();
        target = &holder->as_element();
    }
    target->kind_ = kind;
    target->value = is_deleted(bk) ? JsonVal{} : diff.value;
    target->old_value = old_val;
    target->version = version;
    return holder;
}

// ── _prepare_array_delta ──

struct PreparedDelta {
    vector<DeltaNodePtr> diffs;
    vector<int> indices;
    vector<int> order;
};

static PreparedDelta prepare_array_delta(
    const JsonArrayState& base,
    const DeltaArray& delta,
    bool is_override)
{
    unordered_map<int, int> id_remap;

    if (is_override) {
        vector<int> flat_order;
        for (int eid : base.order)
            if (eid != 0 && eid != -1) flat_order.push_back(eid);
        for (int pos = 0; pos < static_cast<int>(flat_order.size()); ++pos)
            id_remap[pos + 1] = flat_order[pos];
    } else {
        int cur_max = 0;
        for (int id : base.indices)
            if (id > cur_max) cur_max = id;
        int next_id = cur_max + 1;

        for (size_t i = 0; i < delta.diffs.size(); ++i) {
            int orig_id = delta.indices[i];
            auto& d = delta.diffs[i];
            if (d->type() == DeltaType::Element &&
                sultan::base_kind(d->kind()) == ChangeKind::Added) {
                id_remap[orig_id] = next_id++;
            } else if (orig_id > delta.base_count) {
                throw std::runtime_error(
                    "CHANGED/DELETED ID exceeds base_count");
            }
        }
    }

    PreparedDelta result;
    for (auto& d : delta.diffs)
        result.diffs.push_back(d->clone());
    result.indices.reserve(delta.indices.size());
    for (int id : delta.indices) {
        auto it = id_remap.find(id);
        result.indices.push_back(it != id_remap.end() ? it->second : id);
    }
    result.order.reserve(delta.order.size());
    for (int id : delta.order) {
        auto it = id_remap.find(id);
        result.order.push_back(it != id_remap.end() ? it->second : id);
    }
    return result;
}

// ── _rebuild_array_order ──

static vector<int> rebuild_array_order(
    const JsonArrayState& base,
    const vector<int>& delta_order)
{
    unordered_set<int> delta_set(delta_order.begin(), delta_order.end());
    unordered_set<int> base_id_set(base.indices.begin(), base.indices.end());

    // orphans: base 中有但 delta_order 中没有的元素
    vector<int> orphans;
    for (int id : base.indices) {
        if (delta_set.count(id) == 0) orphans.push_back(id);
    }
    unordered_set<int> orphan_set(orphans.begin(), orphans.end());

    // 构建 after map：orphan 跟在其最近的锚点之后
    unordered_map<int, vector<int>> after;
    int last_anchor = 0;
    for (int eid : base.order) {
        if (orphan_set.count(eid)) {
            after[last_anchor].push_back(eid);
        } else {
            last_anchor = eid;
        }
    }

    vector<int> new_order;
    for (int eid : delta_order) {
        new_order.push_back(eid);
        auto it = after.find(eid);
        if (it != after.end()) {
            for (int ob : it->second)
                new_order.push_back(ob);
        }
    }
    return new_order;
}

// ── 前置声明 ──

static StateNodePtr apply_delta_entry(
    const DeltaBase& diff,
    StateBase* existing,
    vector<string>* field_path,
    int version,
    bool is_override);

// ── apply_array_delta ──

void apply_array_delta(
    JsonArrayState& base,
    const DeltaArray& delta,
    vector<string>* field_path,
    int version,
    bool is_override)
{
    base.kind_ = delta.kind_;
    base.version = version;
    auto prepared = prepare_array_delta(base, delta, is_override);

    unordered_map<int, int> id_map;
    for (int i = 0; i < static_cast<int>(base.indices.size()); ++i)
        id_map[base.indices[i]] = i;

    for (size_t i = 0; i < prepared.diffs.size(); ++i) {
        int elem_id = prepared.indices[i];
        auto it = id_map.find(elem_id);
        StateBase* existing = (it != id_map.end()) ? base.diffs[it->second].get() : nullptr;

        auto applied = apply_delta_entry(*prepared.diffs[i], existing, field_path, version, is_override);
        if (!applied) continue;

        if (it == id_map.end()) {
            base.diffs.push_back(std::move(applied));
            base.indices.push_back(elem_id);
        } else {
            base.diffs[it->second] = std::move(applied);
        }
    }

    base.order = rebuild_array_order(base, prepared.order);
}

// ── apply_dict_delta ──

void apply_dict_delta(
    JsonDictState& base,
    const DeltaDict& delta,
    vector<string>* field_path,
    int version,
    bool is_override)
{
    base.kind_ = delta.kind_;
    base.version = version;

    for (auto& [key, diff] : delta.items) {
        if (field_path) field_path->push_back(key);

        StateBase* existing = base.find(key);

        // DupList 归一化
        bool existing_dup = existing && existing->is_array() &&
                            existing->as_array().is_duplist;
        bool diff_dup = diff->type() == DeltaType::Array &&
                        diff->as_array().is_duplist;
        DeltaNodePtr diff_wrapper;
        if (existing_dup && !diff_dup) {
            diff_wrapper = DeltaArray::wrap(diff->clone(), true);
        }
        if (diff_dup && !existing_dup && existing) {
            base.insert(key, JsonArrayState::wrap(std::move(base.entries[key]), true));
            existing = base.find(key);
        }

        auto applied = apply_delta_entry(
            diff_wrapper ? *diff_wrapper : *diff,
            existing, field_path, version, is_override);
        if (applied)
            base.insert(key, std::move(applied));
        if (field_path) field_path->pop_back();
    }
}

// ── _apply_delta_entry ──

static string format_field_path(const vector<string>* field_path) {
    if (!field_path || field_path->empty()) return "";
    string s;
    for (size_t i = 0; i < field_path->size(); ++i) {
        if (i > 0) s += '.';
        s += (*field_path)[i];
    }
    return s;
}

static StateNodePtr apply_delta_entry(
    const DeltaBase& diff,
    StateBase* existing,
    vector<string>* field_path,
    int version,
    bool is_override)
{
    // 已被前一 mod 删除的节点，后续 mod 不再修改
    if (existing && is_deleted(base_kind(existing->kind())))
        return nullptr;

    // ── 阶段1：预处理 ──

    DeltaNodePtr diff_holder;
    StateNodePtr existing_holder;
    const DeltaBase* dp = &diff;
    StateBase* ep = existing;

    // DeltaElement(DELETED) + existing=dict/array → 展开 diff
    if (dp->type() == DeltaType::Element &&
        sultan::base_kind(dp->kind()) == ChangeKind::Deleted && ep)
    {
        auto& elem = dp->as_element();
        if (ep->is_dict() && elem.value.is_obj()) {
            auto expanded = make_unique<DeltaDict>();
            expanded->kind_ = ChangeKind::Deleted;
            auto it = elem.value.obj_iter();
            JsonVal::ObjEntry oe;
            while (it.next(oe)) {
                string key(oe.key, oe.key_len);
                expanded->insert(std::move(key),
                    make_delta_element(ChangeKind::Deleted, oe.val));
            }
            diff_holder = std::move(expanded);
            dp = diff_holder.get();
        } else if (ep->is_array() && elem.value.is_arr()) {
            auto expanded = make_unique<DeltaArray>();
            expanded->kind_ = ChangeKind::Deleted;
            expanded->is_duplist = ep->as_array().is_duplist;
            int n = 0;
            auto arr_it = elem.value.arr_iter();
            JsonVal v;
            while (arr_it.next(v)) {
                expanded->diffs.push_back(
                    make_delta_element(ChangeKind::Deleted, v));
                expanded->indices.push_back(++n);
            }
            expanded->base_count = n;
            expanded->order.push_back(0);
            for (int i = 1; i <= n; ++i) expanded->order.push_back(i);
            expanded->order.push_back(-1);
            diff_holder = std::move(expanded);
            dp = diff_holder.get();
        }
    }

    // DeltaArray + existing=element → wrap existing
    if (dp->type() == DeltaType::Array && ep && ep->is_element()) {
        existing_holder = JsonArrayState::wrap(existing->clone(), dp->as_array().is_duplist);
        ep = existing_holder.get();
    }
    // DeltaArray + existing=nullptr → 创建空 ArrayState（DupList 新增场景）
    else if (dp->type() == DeltaType::Array && !ep) {
        existing_holder = make_unique<JsonArrayState>();
        existing_holder->as_array().is_duplist = dp->as_array().is_duplist;
        ep = existing_holder.get();
    }
    // DeltaElement + existing=array → wrap diff
    else if (dp->type() == DeltaType::Element && ep && ep->is_array()) {
        diff_holder = DeltaArray::wrap(dp->clone(), ep->as_array().is_duplist);
        dp = diff_holder.get();
    }

    // ── 阶段2：类型检查 ──

    if (ep) {
        bool type_match =
            (dp->type() == DeltaType::Element && ep->is_element()) ||
            (dp->type() == DeltaType::Dict && ep->is_dict()) ||
            (dp->type() == DeltaType::Array && ep->is_array());
        if (!type_match) {
            diag_manager().error("merge",
                format_field_path(field_path) +
                ": \xe5\xad\x97\xe6\xae\xb5\xe7\xb1\xbb\xe5\x9e\x8b\xe4\xb8\x8d\xe5\x8c\xb9\xe9\x85\x8d\xef\xbc\x8c\xe8\xaf\xa5 Mod \xe5\x8f\xaf\xe8\x83\xbd\xe5\x9f\xba\xe4\xba\x8e\xe6\x97\xa7\xe7\x89\x88\xe6\x9c\xac\xe6\xb8\xb8\xe6\x88\x8f\xe5\x88\xb6\xe4\xbd\x9c");
            return nullptr;
        }
    }
    if (!ep && dp->type() != DeltaType::Element) {
        diag_manager().error("merge",
            format_field_path(field_path) +
            ": \xe5\xad\x97\xe6\xae\xb5\xe5\x9c\xa8\xe6\x9c\xac\xe4\xbd\x93\xe4\xb8\xad\xe4\xb8\x8d\xe5\xad\x98\xe5\x9c\xa8\xef\xbc\x8c\xe8\xaf\xa5 Mod \xe5\x8f\xaf\xe8\x83\xbd\xe5\x9f\xba\xe4\xba\x8e\xe6\x97\xa7\xe7\x89\x88\xe6\x9c\xac\xe6\xb8\xb8\xe6\x88\x8f\xe5\x88\xb6\xe4\xbd\x9c");
        return nullptr;
    }

    // ── 阶段3：分派 ──

    if (dp->type() == DeltaType::Element) {
        JsonElementState* ee = (ep && ep->is_element()) ? &ep->as_element() : nullptr;
        return apply_field_delta(dp->as_element(), ee, version, is_override);
    }

    if (dp->type() == DeltaType::Dict)
        apply_dict_delta(ep->as_dict(), dp->as_dict(), field_path, version, is_override);
    else
        apply_array_delta(ep->as_array(), dp->as_array(), field_path, version, is_override);

    return std::move(existing_holder);
}

// ── apply_delta_to_state ──

void apply_delta_to_state(
    JsonState& state,
    const DeltaDict& delta,
    vector<string>* field_path,
    int version,
    bool is_override)
{
    SULTAN_PERF_SCOPE("apply_delta");
    if (!state.valid())
        throw std::runtime_error("apply_delta_to_state: invalid state");
    if (!state.root().is_dict())
        throw std::runtime_error("apply_delta_to_state: root must be dict");
    apply_dict_delta(state.root().as_dict(), delta, field_path, version, is_override);
}

}  // namespace sultan
