#pragma once
#include <cstddef>
#include <cstdint>
#include <string>

struct yyjson_mut_doc;
struct yyjson_mut_val;

namespace sultan {

enum class JsonType : uint8_t;

class MutVal {
    yyjson_mut_doc* doc_ = nullptr;
    yyjson_mut_val* val_ = nullptr;
public:
    MutVal() = default;
    MutVal(yyjson_mut_doc* doc, yyjson_mut_val* val) : doc_(doc), val_(val) {}

    bool valid() const { return val_ != nullptr; }
    explicit operator bool() const { return valid(); }

    // ── 类型 ──

    JsonType type() const;
    bool is_null() const;
    bool is_bool() const;
    bool is_int() const;
    bool is_real() const;
    bool is_str() const;
    bool is_obj() const;
    bool is_arr() const;

    // ── 读取 ──

    bool        get_bool() const;
    int64_t     get_int() const;
    double      get_real() const;
    const char* get_str() const;
    size_t      get_len() const;

    // ── 修改标量 ──

    void set_bool(bool v);
    void set_int(int64_t v);
    void set_real(double v);
    void set_str(const std::string& s);
    void set_str(const char* s, size_t len);

    // ── 对象操作 ──

    MutVal obj_get(const char* key) const;
    void   obj_add(const std::string& key, MutVal val);
    void   obj_add(const char* key, size_t key_len, MutVal val);
    void   obj_put(const std::string& key, MutVal val);
    bool   obj_remove(const char* key);
    size_t obj_size() const;

    // ── 数组操作 ──

    MutVal arr_get(size_t idx) const;
    void   arr_append(MutVal val);
    void   arr_prepend(MutVal val);
    bool   arr_remove(size_t idx);
    size_t arr_size() const;

    // ── 值创建 ──

    MutVal new_null() const;
    MutVal new_bool(bool v) const;
    MutVal new_int(int64_t v) const;
    MutVal new_real(double v) const;
    MutVal new_str(const std::string& s) const;
    MutVal new_str(const char* s, size_t len) const;
    MutVal new_obj() const;
    MutVal new_arr() const;

    // ── 迭代器 ──

    struct ObjEntry;
    class ObjIter;
    class ArrIter;
    ObjIter obj_iter() const;
    ArrIter arr_iter() const;

    // ── 内部访问 ──

    yyjson_mut_val* raw() const { return val_; }
    yyjson_mut_doc* raw_doc() const { return doc_; }
};

struct MutVal::ObjEntry {
    const char* key_str;
    size_t key_len;
    MutVal key;
    MutVal val;
};

class MutVal::ObjIter {
    alignas(8) char buf_[64]{};
    yyjson_mut_doc* doc_ = nullptr;
public:
    ObjIter(yyjson_mut_val* obj, yyjson_mut_doc* doc);
    bool next(ObjEntry& out);
};

class MutVal::ArrIter {
    alignas(8) char buf_[64]{};
    yyjson_mut_doc* doc_ = nullptr;
public:
    ArrIter(yyjson_mut_val* arr, yyjson_mut_doc* doc);
    bool next(MutVal& out);
};

}  // namespace sultan
