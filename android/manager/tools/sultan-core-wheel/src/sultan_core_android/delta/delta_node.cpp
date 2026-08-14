#include "delta_node.h"
#include "json_doc.h"
#include "json_val.h"
#include "mut_doc.h"
#include "mut_val.h"
#include <stdexcept>

namespace sultan {

using std::make_unique;

// ── DeltaBase 类型转换 ──

DeltaElement& DeltaBase::as_element() {
    if (type() != DeltaType::Element)
        throw std::runtime_error("DeltaBase::as_element: not an element");
    return static_cast<DeltaElement&>(*this);
}

DeltaDict& DeltaBase::as_dict() {
    if (type() != DeltaType::Dict)
        throw std::runtime_error("DeltaBase::as_dict: not a dict");
    return static_cast<DeltaDict&>(*this);
}

DeltaArray& DeltaBase::as_array() {
    if (type() != DeltaType::Array)
        throw std::runtime_error("DeltaBase::as_array: not an array");
    return static_cast<DeltaArray&>(*this);
}

const DeltaElement& DeltaBase::as_element() const {
    if (type() != DeltaType::Element)
        throw std::runtime_error("DeltaBase::as_element: not an element");
    return static_cast<const DeltaElement&>(*this);
}

const DeltaDict& DeltaBase::as_dict() const {
    if (type() != DeltaType::Dict)
        throw std::runtime_error("DeltaBase::as_dict: not a dict");
    return static_cast<const DeltaDict&>(*this);
}

const DeltaArray& DeltaBase::as_array() const {
    if (type() != DeltaType::Array)
        throw std::runtime_error("DeltaBase::as_array: not an array");
    return static_cast<const DeltaArray&>(*this);
}

// ── DeltaElement ──

DeltaNodePtr DeltaElement::clone() const {
    auto p = make_unique<DeltaElement>();
    p->kind_ = kind_;
    p->value = value;
    return p;
}

// ── DeltaDict ──

DeltaNodePtr DeltaDict::clone() const {
    auto p = make_unique<DeltaDict>();
    p->kind_ = kind_;
    for (const auto& [k, v] : items)
        p->items.emplace(k, v->clone());
    return p;
}

DeltaBase* DeltaDict::find(const string& key) const {
    auto it = items.find(key);
    return (it != items.end()) ? it->second.get() : nullptr;
}

void DeltaDict::insert(string key, DeltaNodePtr node) {
    items.insert_or_assign(std::move(key), std::move(node));
}

// ── DeltaArray ──

DeltaNodePtr DeltaArray::clone() const {
    auto p = make_unique<DeltaArray>();
    p->kind_ = kind_;
    p->base_count = base_count;
    p->indices = indices;
    p->order = order;
    p->is_duplist = is_duplist;
    for (const auto& d : diffs)
        p->diffs.push_back(d->clone());
    return p;
}

DeltaNodePtr DeltaArray::wrap(DeltaNodePtr value, bool is_dup) {
    auto arr = make_unique<DeltaArray>();
    arr->is_duplist = is_dup;
    if (value) {
        arr->base_count = 1;
        arr->indices.push_back(1);
        arr->order = {0, 1, -1};
        arr->diffs.push_back(std::move(value));
    } else {
        arr->base_count = 0;
        arr->order = {0, -1};
    }
    return arr;
}

// ── 工厂 ──

DeltaNodePtr make_delta_element(ChangeKind kind, JsonVal value) {
    auto p = make_unique<DeltaElement>();
    p->kind_ = kind;
    p->value = value;
    return p;
}

// ── 序列化：DeltaBase → JsonDoc ──

static MutVal serialize_node(const DeltaBase& node, MutVal ctx);

static MutVal serialize_element(const DeltaElement& e, MutVal ctx) {
    auto obj = ctx.new_obj();
    obj.obj_add("__type", ctx.new_str("field"));
    obj.obj_add("kind", ctx.new_int(static_cast<int64_t>(static_cast<uint8_t>(e.kind_))));
    if (e.value.valid())
        obj.obj_add("value", val_to_mut(e.value, ctx));
    return obj;
}

static MutVal serialize_dict(const DeltaDict& d, MutVal ctx) {
    auto obj = ctx.new_obj();
    obj.obj_add("__type", ctx.new_str("dict_delta"));
    obj.obj_add("kind", ctx.new_int(static_cast<int64_t>(static_cast<uint8_t>(d.kind_))));
    auto items_obj = ctx.new_obj();
    for (auto& [k, v] : d.items)
        items_obj.obj_add(k, serialize_node(*v, ctx));
    obj.obj_add("items", items_obj);
    return obj;
}

static MutVal serialize_array(const DeltaArray& a, MutVal ctx) {
    auto obj = ctx.new_obj();
    obj.obj_add("__type", ctx.new_str("array_delta"));
    obj.obj_add("kind", ctx.new_int(static_cast<int64_t>(static_cast<uint8_t>(a.kind_))));
    obj.obj_add("base_count", ctx.new_int(static_cast<int64_t>(a.base_count)));
    obj.obj_add("is_duplist", ctx.new_bool(a.is_duplist));

    auto indices_arr = ctx.new_arr();
    for (int id : a.indices)
        indices_arr.arr_append(ctx.new_int(static_cast<int64_t>(id)));
    obj.obj_add("indices", indices_arr);

    auto order_arr = ctx.new_arr();
    for (int id : a.order)
        order_arr.arr_append(ctx.new_int(static_cast<int64_t>(id)));
    obj.obj_add("order", order_arr);

    auto diffs_arr = ctx.new_arr();
    for (auto& d : a.diffs)
        diffs_arr.arr_append(serialize_node(*d, ctx));
    obj.obj_add("diffs", diffs_arr);

    return obj;
}

static MutVal serialize_node(const DeltaBase& node, MutVal ctx) {
    switch (node.type()) {
        case DeltaType::Element: return serialize_element(node.as_element(), ctx);
        case DeltaType::Dict:    return serialize_dict(node.as_dict(), ctx);
        case DeltaType::Array:   return serialize_array(node.as_array(), ctx);
    }
    return ctx.new_null();
}

JsonDoc serialize_delta(const DeltaBase& delta) {
    MutDoc md;
    auto ctx = md.root();
    md.set_root(serialize_node(delta, ctx));
    return md.freeze();
}

// ── 反序列化：JsonDoc → DeltaNodePtr ──

static DeltaNodePtr deserialize_node(JsonVal v);

static DeltaNodePtr deserialize_element(JsonVal v) {
    auto p = make_unique<DeltaElement>();
    p->kind_ = static_cast<ChangeKind>(static_cast<uint8_t>(v.obj_get("kind").get_int()));
    p->value = v.obj_get("value");
    return p;
}

static DeltaNodePtr deserialize_dict(JsonVal v) {
    auto p = make_unique<DeltaDict>();
    p->kind_ = static_cast<ChangeKind>(static_cast<uint8_t>(v.obj_get("kind").get_int()));

    JsonVal items = v.obj_get("items");
    if (items.valid() && items.is_obj()) {
        auto it = items.obj_iter();
        JsonVal::ObjEntry e;
        while (it.next(e)) {
            string key(e.key, e.key_len);
            p->insert(std::move(key), deserialize_node(e.val));
        }
    }
    return p;
}

static DeltaNodePtr deserialize_array(JsonVal v) {
    auto p = make_unique<DeltaArray>();
    p->kind_ = static_cast<ChangeKind>(static_cast<uint8_t>(v.obj_get("kind").get_int()));
    p->base_count = static_cast<int>(v.obj_get("base_count").get_int());
    p->is_duplist = v.obj_get("is_duplist").get_bool();

    JsonVal indices = v.obj_get("indices");
    if (indices.valid()) {
        auto it = indices.arr_iter();
        JsonVal elem;
        while (it.next(elem))
            p->indices.push_back(static_cast<int>(elem.get_int()));
    }

    JsonVal order = v.obj_get("order");
    if (order.valid()) {
        auto it = order.arr_iter();
        JsonVal elem;
        while (it.next(elem))
            p->order.push_back(static_cast<int>(elem.get_int()));
    }

    JsonVal diffs = v.obj_get("diffs");
    if (diffs.valid()) {
        auto it = diffs.arr_iter();
        JsonVal elem;
        while (it.next(elem))
            p->diffs.push_back(deserialize_node(elem));
    }

    return p;
}

static DeltaNodePtr deserialize_node(JsonVal v) {
    if (!v.valid() || !v.is_obj())
        throw std::runtime_error("deserialize_delta: expected object");

    JsonVal type_val = v.obj_get("__type");
    if (!type_val.valid() || !type_val.is_str())
        throw std::runtime_error("deserialize_delta: missing __type");

    string t(type_val.get_str());
    if (t == "field")       return deserialize_element(v);
    if (t == "dict_delta")  return deserialize_dict(v);
    if (t == "array_delta") return deserialize_array(v);
    throw std::runtime_error("deserialize_delta: unknown __type: " + t);
}

DeltaNodePtr deserialize_delta(const JsonDoc& doc) {
    if (!doc.valid())
        throw std::runtime_error("deserialize_delta: invalid doc");
    return deserialize_node(doc.root());
}

// ── 展平 ──

static void flatten_node(const DeltaBase& node, vector<string>& path,
                          vector<FlatField>& out) {
    switch (node.type()) {
        case DeltaType::Element: {
            auto& e = node.as_element();
            out.push_back({vector<string>(path), base_kind(e.kind_), serialize_val(e.value)});
            break;
        }
        case DeltaType::Dict: {
            auto& dict = node.as_dict();
            for (const auto& [key, child] : dict.items) {
                path.push_back(key);
                flatten_node(*child, path, out);
                path.pop_back();
            }
            break;
        }
        case DeltaType::Array: {
            auto& arr = node.as_array();
            for (size_t i = 0; i < arr.diffs.size(); ++i) {
                path.push_back("[" + std::to_string(arr.indices[i]) + "]");
                flatten_node(*arr.diffs[i], path, out);
                path.pop_back();
            }
            break;
        }
    }
}

vector<FlatField> flatten_delta(const DeltaDict& root) {
    vector<FlatField> out;
    vector<string> path;
    for (const auto& [key, node] : root.items) {
        path.push_back(key);
        flatten_node(*node, path, out);
        path.pop_back();
    }
    return out;
}

}  // namespace sultan
