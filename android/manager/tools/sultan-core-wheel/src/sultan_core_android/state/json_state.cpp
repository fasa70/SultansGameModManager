#include "json_state.h"
#include "json_doc.h"
#include "json_val.h"
#include "mut_doc.h"
#include "mut_val.h"
#include "perf.h"
#include <algorithm>
#include <stdexcept>
#include <unordered_map>

namespace sultan {

using std::make_unique;
using std::unordered_map;

// ── JsonVal → StateNodePtr 递归 ──

StateNodePtr make_dict(JsonVal obj) {
    auto dict = make_unique<JsonDictState>();

    struct KeyValues { vector<JsonVal> vals; };
    unordered_map<string, KeyValues> seen;
    vector<string> key_order;

    auto it = obj.obj_iter();
    JsonVal::ObjEntry e;
    while (it.next(e)) {
        string k(e.key, e.key_len);
        auto& kv = seen[k];
        if (kv.vals.empty()) key_order.push_back(k);
        kv.vals.push_back(e.val);
    }

    for (auto& k : key_order) {
        auto& kv = seen[k];
        if (kv.vals.size() > 1) {
            auto arr = make_unique<JsonArrayState>();
            arr->is_duplist = true;
            int n = static_cast<int>(kv.vals.size());
            arr->base_count = n;
            for (int i = 0; i < n; ++i) {
                arr->diffs.push_back(make_node(kv.vals[i]));
                arr->indices.push_back(i + 1);
            }
            arr->order.push_back(0);
            for (int i = 1; i <= n; ++i) arr->order.push_back(i);
            arr->order.push_back(-1);
            dict->insert(std::move(k), std::move(arr));
        } else {
            dict->insert(std::move(k), make_node(kv.vals[0]));
        }
    }
    return dict;
}

StateNodePtr make_array(JsonVal arr) {
    auto node = make_unique<JsonArrayState>();
    int n = static_cast<int>(arr.arr_size());
    node->base_count = n;

    auto it = arr.arr_iter();
    JsonVal elem;
    int idx = 1;
    while (it.next(elem)) {
        node->diffs.push_back(make_node(elem));
        node->indices.push_back(idx++);
    }

    node->order.push_back(0);
    for (int i = 1; i <= n; ++i) node->order.push_back(i);
    node->order.push_back(-1);
    return node;
}

StateNodePtr make_node(JsonVal v) {
    switch (v.type()) {
        case JsonType::Obj: return make_dict(v);
        case JsonType::Arr: return make_array(v);
        default:            return make_element(v);
    }
}

// ── StateNodePtr → MutVal 递归 ──

static MutVal element_to_val(const JsonElementState& elem, MutVal ctx) {
    return val_to_mut(elem.value, ctx);
}

static MutVal state_to_val(const StateBase& node, MutVal ctx);

static MutVal dict_to_val(const JsonDictState& dict, MutVal ctx) {
    auto obj = ctx.new_obj();

    for (auto& [k, child] : dict.entries) {
        if (!child) continue;
        if (is_deleted(base_kind(child->kind()))) continue;

        if (child->is_array() && child->as_array().is_duplist) {
            auto& arr = child->as_array();
            unordered_map<int, size_t> id_to_idx;
            for (size_t i = 0; i < arr.indices.size(); ++i)
                id_to_idx[arr.indices[i]] = i;

            for (int eid : arr.order) {
                if (eid == 0 || eid == -1) continue;
                auto idx_it = id_to_idx.find(eid);
                if (idx_it == id_to_idx.end()) continue;
                auto& elem = arr.diffs[idx_it->second];
                if (!elem || is_deleted(base_kind(elem->kind()))) continue;
                obj.obj_add(k, state_to_val(*elem, ctx));
            }
        } else {
            obj.obj_add(k, state_to_val(*child, ctx));
        }
    }
    return obj;
}

static MutVal array_to_val(const JsonArrayState& arr, MutVal ctx) {
    auto marr = ctx.new_arr();

    unordered_map<int, size_t> id_to_idx;
    for (size_t i = 0; i < arr.indices.size(); ++i)
        id_to_idx[arr.indices[i]] = i;

    for (int eid : arr.order) {
        if (eid == 0 || eid == -1) continue;
        auto it = id_to_idx.find(eid);
        if (it == id_to_idx.end()) continue;
        auto& elem = arr.diffs[it->second];
        if (!elem || is_deleted(base_kind(elem->kind()))) continue;
        marr.arr_append(state_to_val(*elem, ctx));
    }
    return marr;
}

static MutVal state_to_val(const StateBase& node, MutVal ctx) {
    if (node.is_element())
        return element_to_val(node.as_element(), ctx);
    if (node.is_dict())
        return dict_to_val(node.as_dict(), ctx);
    return array_to_val(node.as_array(), ctx);
}

// ── JsonState 实现 ──

JsonState::JsonState(StateNodePtr root) : root_(std::move(root)) {}

JsonState JsonState::from_doc(const JsonDoc& doc) {
    SULTAN_PERF_SCOPE("state_from_doc");
    if (!doc.valid())
        throw std::runtime_error("JsonState::from_doc: invalid JsonDoc");
    return JsonState(make_node(doc.root()));
}

JsonState JsonState::from_node(StateNodePtr root) {
    return JsonState(std::move(root));
}

JsonDoc JsonState::to_doc() const {
    if (!root_)
        throw std::runtime_error("JsonState::to_doc: empty state");
    MutDoc d;
    auto ctx = d.root();
    d.set_root(state_to_val(*root_, ctx));
    return d.freeze();
}

FormatResult JsonState::format(int highlight_version) const {
    if (!root_)
        return {};
    return format_state(*root_, highlight_version);
}

JsonState JsonState::clone() const {
    if (!root_)
        return JsonState();
    return JsonState(root_->clone());
}

StateBase& JsonState::root() {
    if (!root_) throw std::runtime_error("JsonState::root: empty state");
    return *root_;
}

const StateBase& JsonState::root() const {
    if (!root_) throw std::runtime_error("JsonState::root: empty state");
    return *root_;
}

}  // namespace sultan
