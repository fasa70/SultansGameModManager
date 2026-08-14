#include "state_node.h"
#include <stdexcept>

namespace sultan {

// ── StateBase ──

JsonElementState& StateBase::as_element() {
    if (state_type_ != StateType::Element)
        throw std::runtime_error("StateBase: not an element");
    return static_cast<JsonElementState&>(*this);
}
JsonDictState& StateBase::as_dict() {
    if (state_type_ != StateType::Dict)
        throw std::runtime_error("StateBase: not a dict");
    return static_cast<JsonDictState&>(*this);
}
JsonArrayState& StateBase::as_array() {
    if (state_type_ != StateType::Array)
        throw std::runtime_error("StateBase: not an array");
    return static_cast<JsonArrayState&>(*this);
}
const JsonElementState& StateBase::as_element() const {
    if (state_type_ != StateType::Element)
        throw std::runtime_error("StateBase: not an element");
    return static_cast<const JsonElementState&>(*this);
}
const JsonDictState& StateBase::as_dict() const {
    if (state_type_ != StateType::Dict)
        throw std::runtime_error("StateBase: not a dict");
    return static_cast<const JsonDictState&>(*this);
}
const JsonArrayState& StateBase::as_array() const {
    if (state_type_ != StateType::Array)
        throw std::runtime_error("StateBase: not an array");
    return static_cast<const JsonArrayState&>(*this);
}

// ── JsonElementState ──

bool JsonElementState::is_modified() const {
    return base_kind(kind_) != ChangeKind::Origin;
}

StateNodePtr JsonElementState::clone() const {
    auto node = std::make_unique<JsonElementState>();
    node->kind_ = kind_;
    node->value = value;
    node->old_value = old_value;
    node->version = version;
    return node;
}

// ── JsonDictState ──

bool JsonDictState::is_modified() const {
    for (auto& [key, child] : entries) {
        if (child && child->is_modified()) return true;
    }
    return false;
}

StateNodePtr JsonDictState::clone() const {
    auto node = std::make_unique<JsonDictState>();
    node->kind_ = kind_;
    node->version = version;
    for (auto& [key, child] : entries) {
        node->entries.emplace(key, child ? child->clone() : nullptr);
    }
    return node;
}

StateBase* JsonDictState::find(const string& key) const {
    auto it = entries.find(key);
    if (it == entries.end()) return nullptr;
    return it->second.get();
}

void JsonDictState::insert(string key, StateNodePtr node) {
    entries.insert_or_assign(std::move(key), std::move(node));
}

// ── JsonArrayState ──

bool JsonArrayState::is_modified() const {
    for (auto& child : diffs) {
        if (child && child->is_modified()) return true;
    }
    return false;
}

StateNodePtr JsonArrayState::clone() const {
    auto node = std::make_unique<JsonArrayState>();
    node->kind_ = kind_;
    node->version = version;
    node->base_count = base_count;
    node->indices = indices;
    node->order = order;
    node->is_duplist = is_duplist;
    for (auto& child : diffs) {
        node->diffs.push_back(child ? child->clone() : nullptr);
    }
    return node;
}

StateNodePtr JsonArrayState::wrap(StateNodePtr value, bool is_dup) {
    auto arr = std::make_unique<JsonArrayState>();
    arr->is_duplist = is_dup;
    if (value) {
        arr->base_count = 1;
        arr->diffs.push_back(std::move(value));
        arr->indices.push_back(1);
        arr->order = {0, 1, -1};
    } else {
        arr->base_count = 0;
        arr->order = {0, -1};
    }
    return arr;
}

// ── 工厂 ──

StateNodePtr make_element(JsonVal v, ChangeKind kind) {
    auto node = std::make_unique<JsonElementState>();
    node->kind_ = kind;
    node->value = v;
    return node;
}

}  // namespace sultan
