#pragma once
#include "change_kind.h"
#include "json_val.h"
#include <cstdint>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

namespace sultan {

using std::string;
using std::unique_ptr;
using std::unordered_map;
using std::vector;

class JsonDoc;

enum class DeltaType : uint8_t { Element, Dict, Array };

struct DeltaElement;
struct DeltaDict;
struct DeltaArray;

struct DeltaBase {
    virtual ~DeltaBase() = default;
    DeltaBase() = default;
    DeltaBase(const DeltaBase&) = delete;
    DeltaBase& operator=(const DeltaBase&) = delete;
    DeltaBase(DeltaBase&&) = default;
    DeltaBase& operator=(DeltaBase&&) = default;

    virtual DeltaType type() const = 0;
    virtual ChangeKind kind() const = 0;
    virtual unique_ptr<DeltaBase> clone() const = 0;

    DeltaElement& as_element();
    DeltaDict& as_dict();
    DeltaArray& as_array();
    const DeltaElement& as_element() const;
    const DeltaDict& as_dict() const;
    const DeltaArray& as_array() const;
};

using DeltaNodePtr = unique_ptr<DeltaBase>;

struct DeltaElement : DeltaBase {
    ChangeKind kind_ = ChangeKind::Origin;
    JsonVal value;

    DeltaType type() const override { return DeltaType::Element; }
    ChangeKind kind() const override { return kind_; }
    DeltaNodePtr clone() const override;
};

struct DeltaDict : DeltaBase {
    ChangeKind kind_ = ChangeKind::Origin;
    unordered_map<string, DeltaNodePtr> items;

    DeltaType type() const override { return DeltaType::Dict; }
    ChangeKind kind() const override { return kind_; }
    DeltaNodePtr clone() const override;

    DeltaBase* find(const string& key) const;
    void insert(string key, DeltaNodePtr node);
    size_t size() const { return items.size(); }
    bool empty() const { return items.empty(); }
};

struct DeltaArray : DeltaBase {
    ChangeKind kind_ = ChangeKind::Origin;
    vector<DeltaNodePtr> diffs;
    int base_count = 0;
    vector<int> indices;
    vector<int> order;
    bool is_duplist = false;

    DeltaType type() const override { return DeltaType::Array; }
    ChangeKind kind() const override { return kind_; }
    DeltaNodePtr clone() const override;

    static DeltaNodePtr wrap(DeltaNodePtr value, bool is_dup);
};

// ── 工厂 ──

// JsonVal 引用源 JsonDoc 数据，调用方需保证 JsonDoc 生命周期覆盖返回值使用期
DeltaNodePtr make_delta_element(ChangeKind kind, JsonVal value);

inline std::unique_ptr<DeltaDict> to_delta_dict(DeltaNodePtr node) {
    if (!node || node->type() != DeltaType::Dict) return nullptr;
    return unique_ptr<DeltaDict>(static_cast<DeltaDict*>(node.release()));
}

// ── 序列化 ──

JsonDoc serialize_delta(const DeltaBase& delta);
DeltaNodePtr deserialize_delta(const JsonDoc& doc);

// ── 展平 ──

struct FlatField {
    vector<string> path;
    ChangeKind kind;
    string value_str;
};

vector<FlatField> flatten_delta(const DeltaDict& root);

}  // namespace sultan
