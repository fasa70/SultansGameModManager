#include "mut_val.h"
#include "json_val.h"
#include "yyjson.h"

namespace sultan {

static_assert(sizeof(yyjson_mut_obj_iter) <= 64, "MutVal::ObjIter buf_ too small");
static_assert(sizeof(yyjson_mut_arr_iter) <= 64, "MutVal::ArrIter buf_ too small");

// ── 类型 ──

JsonType MutVal::type() const {
    if (!val_) return JsonType::Null;
    if (yyjson_mut_is_null(val_))  return JsonType::Null;
    if (yyjson_mut_is_bool(val_))  return JsonType::Bool;
    if (yyjson_mut_is_int(val_))   return JsonType::Int;
    if (yyjson_mut_is_real(val_))  return JsonType::Real;
    if (yyjson_mut_is_str(val_))   return JsonType::Str;
    if (yyjson_mut_is_obj(val_))   return JsonType::Obj;
    if (yyjson_mut_is_arr(val_))   return JsonType::Arr;
    return JsonType::Null;
}

bool MutVal::is_null() const { return type() == JsonType::Null; }
bool MutVal::is_bool() const { return type() == JsonType::Bool; }
bool MutVal::is_int()  const { return type() == JsonType::Int; }
bool MutVal::is_real() const { return type() == JsonType::Real; }
bool MutVal::is_str()  const { return type() == JsonType::Str; }
bool MutVal::is_obj()  const { return type() == JsonType::Obj; }
bool MutVal::is_arr()  const { return type() == JsonType::Arr; }

// ── 读取 ──

bool MutVal::get_bool() const {
    return val_ ? yyjson_mut_get_bool(val_) : false;
}

int64_t MutVal::get_int() const {
    if (!val_) return 0;
    if (yyjson_mut_is_sint(val_)) return yyjson_mut_get_sint(val_);
    if (yyjson_mut_is_uint(val_)) return static_cast<int64_t>(yyjson_mut_get_uint(val_));
    return 0;
}

double MutVal::get_real() const {
    return val_ ? yyjson_mut_get_real(val_) : 0.0;
}

const char* MutVal::get_str() const {
    return val_ ? yyjson_mut_get_str(val_) : nullptr;
}

size_t MutVal::get_len() const {
    return val_ ? yyjson_mut_get_len(val_) : 0;
}

// ── 修改标量 ──

void MutVal::set_bool(bool v) {
    if (val_) yyjson_mut_set_bool(val_, v);
}

void MutVal::set_int(int64_t v) {
    if (val_) yyjson_mut_set_sint(val_, v);
}

void MutVal::set_real(double v) {
    if (val_) yyjson_mut_set_real(val_, v);
}

void MutVal::set_str(const char* s, size_t len) {
    if (val_ && doc_) {
        auto* copied = yyjson_mut_strncpy(doc_, s, len);
        if (copied) yyjson_mut_set_strn(val_, copied->uni.str, len);
    }
}

void MutVal::set_str(const std::string& s) {
    set_str(s.c_str(), s.size());
}

// ── 对象操作 ──

MutVal MutVal::obj_get(const char* key) const {
    if (!val_) return {};
    auto* v = yyjson_mut_obj_get(val_, key);
    return MutVal(doc_, v);
}

void MutVal::obj_add(const char* key, size_t key_len, MutVal val) {
    if (!val_ || !doc_) return;
    auto* mkey = yyjson_mut_strncpy(doc_, key, key_len);
    yyjson_mut_obj_add(val_, mkey, val.raw());
}

void MutVal::obj_add(const std::string& key, MutVal val) {
    obj_add(key.c_str(), key.size(), val);
}

void MutVal::obj_put(const std::string& key, MutVal val) {
    if (!val_ || !doc_) return;
    auto* mkey = yyjson_mut_strncpy(doc_, key.c_str(), key.size());
    yyjson_mut_obj_put(val_, mkey, val.raw());
}

bool MutVal::obj_remove(const char* key) {
    if (!val_) return false;
    return yyjson_mut_obj_remove_key(val_, key) != nullptr;
}

size_t MutVal::obj_size() const {
    return val_ ? yyjson_mut_obj_size(val_) : 0;
}

// ── 数组操作 ──

MutVal MutVal::arr_get(size_t idx) const {
    if (!val_) return {};
    auto* v = yyjson_mut_arr_get(val_, idx);
    return MutVal(doc_, v);
}

void MutVal::arr_append(MutVal val) {
    if (val_) yyjson_mut_arr_append(val_, val.raw());
}

void MutVal::arr_prepend(MutVal val) {
    if (val_) yyjson_mut_arr_prepend(val_, val.raw());
}

bool MutVal::arr_remove(size_t idx) {
    if (!val_) return false;
    return yyjson_mut_arr_remove(val_, idx) != nullptr;
}

size_t MutVal::arr_size() const {
    return val_ ? yyjson_mut_arr_size(val_) : 0;
}

// ── 值创建 ──

MutVal MutVal::new_null() const { return MutVal(doc_, yyjson_mut_null(doc_)); }
MutVal MutVal::new_bool(bool v) const { return MutVal(doc_, yyjson_mut_bool(doc_, v)); }
MutVal MutVal::new_int(int64_t v) const { return MutVal(doc_, yyjson_mut_sint(doc_, v)); }
MutVal MutVal::new_real(double v) const { return MutVal(doc_, yyjson_mut_real(doc_, v)); }

MutVal MutVal::new_str(const char* s, size_t len) const {
    return MutVal(doc_, yyjson_mut_strncpy(doc_, s, len));
}

MutVal MutVal::new_str(const std::string& s) const {
    return new_str(s.c_str(), s.size());
}

MutVal MutVal::new_obj() const { return MutVal(doc_, yyjson_mut_obj(doc_)); }
MutVal MutVal::new_arr() const { return MutVal(doc_, yyjson_mut_arr(doc_)); }

// ── ObjIter ──

MutVal::ObjIter::ObjIter(yyjson_mut_val* obj, yyjson_mut_doc* doc) : doc_(doc) {
    auto& iter = *reinterpret_cast<yyjson_mut_obj_iter*>(buf_);
    iter = yyjson_mut_obj_iter_with(obj);
}

bool MutVal::ObjIter::next(ObjEntry& out) {
    auto& iter = *reinterpret_cast<yyjson_mut_obj_iter*>(buf_);
    yyjson_mut_val* key = yyjson_mut_obj_iter_next(&iter);
    if (!key) return false;
    out.key_str = yyjson_mut_get_str(key);
    out.key_len = yyjson_mut_get_len(key);
    out.key = MutVal(doc_, key);
    out.val = MutVal(doc_, yyjson_mut_obj_iter_get_val(key));
    return true;
}

MutVal::ObjIter MutVal::obj_iter() const {
    return ObjIter(val_, doc_);
}

// ── ArrIter ──

MutVal::ArrIter::ArrIter(yyjson_mut_val* arr, yyjson_mut_doc* doc) : doc_(doc) {
    auto& iter = *reinterpret_cast<yyjson_mut_arr_iter*>(buf_);
    iter = yyjson_mut_arr_iter_with(arr);
}

bool MutVal::ArrIter::next(MutVal& out) {
    auto& iter = *reinterpret_cast<yyjson_mut_arr_iter*>(buf_);
    auto* val = yyjson_mut_arr_iter_next(&iter);
    if (!val) return false;
    out = MutVal(doc_, val);
    return true;
}

MutVal::ArrIter MutVal::arr_iter() const {
    return ArrIter(val_, doc_);
}

}  // namespace sultan
