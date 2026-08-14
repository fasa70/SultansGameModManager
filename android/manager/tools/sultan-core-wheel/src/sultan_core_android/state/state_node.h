#pragma once
#include "change_kind.h"
#include "json_val.h"
#include <cstdint>
#include <memory>
#include <string>
#include "ordered_map/ordered_map.h"
#include <vector>

namespace sultan {

using std::string;
using std::unique_ptr;
using std::vector;

struct JsonElementState;
struct JsonDictState;
struct JsonArrayState;

enum class StateType : uint8_t { Element, Dict, Array };

struct StateBase {
    StateType state_type_;

    virtual ~StateBase() = default;
    virtual ChangeKind kind() const = 0;
    virtual bool is_modified() const = 0;
    virtual unique_ptr<StateBase> clone() const = 0;

    bool is_element() const { return state_type_ == StateType::Element; }
    bool is_dict() const    { return state_type_ == StateType::Dict; }
    bool is_array() const   { return state_type_ == StateType::Array; }

    JsonElementState& as_element();
    JsonDictState& as_dict();
    JsonArrayState& as_array();
    const JsonElementState& as_element() const;
    const JsonDictState& as_dict() const;
    const JsonArrayState& as_array() const;

protected:
    explicit StateBase(StateType t) : state_type_(t) {}
};

using StateNodePtr = unique_ptr<StateBase>;

// ── 叶子：标量 ──

struct JsonElementState : StateBase {
    JsonElementState() : StateBase(StateType::Element) {}
    ChangeKind kind_ = ChangeKind::Origin;
    JsonVal value;
    JsonVal old_value;  // 无旧值时为 invalid (JsonVal{})
    int version = 0;

    ChangeKind kind() const override { return kind_; }
    bool is_modified() const override;
    StateNodePtr clone() const override;
};

// ── 字典 ──

struct JsonDictState : StateBase {
    ChangeKind kind_ = ChangeKind::Origin;
    int version = 0;
    ordered_map<string, StateNodePtr> entries;

    JsonDictState() : StateBase(StateType::Dict) {}

    ChangeKind kind() const override { return kind_; }
    bool is_modified() const override;
    StateNodePtr clone() const override;

    StateBase* find(const string& key) const;
    void insert(string key, StateNodePtr node);
    size_t size() const { return entries.size(); }
};

// ── 数组 ──

struct JsonArrayState : StateBase {
    JsonArrayState() : StateBase(StateType::Array) {}
    ChangeKind kind_ = ChangeKind::Origin;
    int version = 0;
    vector<StateNodePtr> diffs;
    int base_count = 0;
    vector<int> indices;
    vector<int> order;
    bool is_duplist = false;

    ChangeKind kind() const override { return kind_; }
    bool is_modified() const override;
    StateNodePtr clone() const override;

    static StateNodePtr wrap(StateNodePtr value, bool is_dup);
};

// JsonVal 引用源 JsonDoc 数据，调用方需保证 JsonDoc 生命周期覆盖返回值使用期
StateNodePtr make_element(JsonVal v, ChangeKind kind = ChangeKind::Origin);
StateNodePtr make_dict(JsonVal obj);
StateNodePtr make_array(JsonVal arr);
StateNodePtr make_node(JsonVal v);

}  // namespace sultan
