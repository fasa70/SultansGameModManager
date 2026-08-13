#include "json_val.h"
#include "mut_val.h"
#include "yyjson.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <iomanip>
#include <sstream>

namespace sultan {

using std::string;
using std::vector;

using std::string;

static_assert(sizeof(yyjson_obj_iter) <= 32, "ObjIter buf_ too small");
static_assert(sizeof(yyjson_arr_iter) <= 32, "ArrIter buf_ too small");

// ── JsonVal ──

JsonType JsonVal::type() const {
    if (!val_) return JsonType::Null;
    if (yyjson_is_null(val_))  return JsonType::Null;
    if (yyjson_is_bool(val_))  return JsonType::Bool;
    if (yyjson_is_int(val_))   return JsonType::Int;
    if (yyjson_is_real(val_))  return JsonType::Real;
    if (yyjson_is_str(val_))   return JsonType::Str;
    if (yyjson_is_obj(val_))   return JsonType::Obj;
    if (yyjson_is_arr(val_))   return JsonType::Arr;
    return JsonType::Null;
}

bool JsonVal::get_bool() const {
    return val_ ? yyjson_get_bool(val_) : false;
}

int64_t JsonVal::get_int() const {
    if (!val_) return 0;
    if (yyjson_is_sint(val_)) return yyjson_get_sint(val_);
    if (yyjson_is_uint(val_)) return static_cast<int64_t>(yyjson_get_uint(val_));
    return 0;
}

uint64_t JsonVal::get_uint() const {
    return val_ ? yyjson_get_uint(val_) : 0;
}

double JsonVal::get_real() const {
    return val_ ? yyjson_get_real(val_) : 0.0;
}

const char* JsonVal::get_str() const {
    return val_ ? yyjson_get_str(val_) : nullptr;
}

JsonVal JsonVal::obj_get(const char* key) const {
    if (!val_) return {};
    return JsonVal(yyjson_obj_get(val_, key));
}

size_t JsonVal::obj_size() const {
    return val_ ? yyjson_obj_size(val_) : 0;
}

size_t JsonVal::arr_size() const {
    return val_ ? yyjson_arr_size(val_) : 0;
}

// ── ObjIter ──

JsonVal::ObjIter::ObjIter(yyjson_val* obj) {
    auto& iter = *reinterpret_cast<yyjson_obj_iter*>(buf_);
    iter = yyjson_obj_iter_with(obj);
}

bool JsonVal::ObjIter::next(ObjEntry& out) {
    auto& iter = *reinterpret_cast<yyjson_obj_iter*>(buf_);
    yyjson_val* key = yyjson_obj_iter_next(&iter);
    if (!key) return false;
    out.key = yyjson_get_str(key);
    out.key_len = yyjson_get_len(key);
    out.val = JsonVal(yyjson_obj_iter_get_val(key));
    return true;
}

JsonVal::ObjIter JsonVal::obj_iter() const {
    return ObjIter(val_);
}

// ── ArrIter ──

JsonVal::ArrIter::ArrIter(yyjson_val* arr) {
    auto& iter = *reinterpret_cast<yyjson_arr_iter*>(buf_);
    iter = yyjson_arr_iter_with(arr);
}

bool JsonVal::ArrIter::next(JsonVal& out) {
    auto& iter = *reinterpret_cast<yyjson_arr_iter*>(buf_);
    yyjson_val* val = yyjson_arr_iter_next(&iter);
    if (!val) return false;
    out = JsonVal(val);
    return true;
}

JsonVal::ArrIter JsonVal::arr_iter() const {
    return ArrIter(val_);
}

// ── serialize_val ──

static string quote_str(const char* s) {
    std::ostringstream oss;
    oss << '"';
    for (const char* p = s; *p; ++p) {
        switch (*p) {
            case '"':  oss << "\\\""; break;
            case '\\': oss << "\\\\"; break;
            case '\b': oss << "\\b"; break;
            case '\f': oss << "\\f"; break;
            case '\n': oss << "\\n"; break;
            case '\r': oss << "\\r"; break;
            case '\t': oss << "\\t"; break;
            default:
                if (static_cast<unsigned char>(*p) < 0x20) {
                    oss << "\\u" << std::hex << std::setfill('0')
                        << std::setw(4) << static_cast<int>(*p);
                } else {
                    oss << *p;
                }
        }
    }
    oss << '"';
    return oss.str();
}

string serialize_val(JsonVal v) {
    if (!v.valid()) return "null";
    switch (v.type()) {
        case JsonType::Null: return "null";
        case JsonType::Bool: return v.get_bool() ? "true" : "false";
        case JsonType::Int:  return std::to_string(v.get_int());
        case JsonType::Real: {
            double d = v.get_real();
            if (std::isinf(d) || std::isnan(d)) return "null";
            std::ostringstream oss;
            oss << std::setprecision(17) << d;
            string s = oss.str();
            if (s.find('.') == string::npos && s.find('e') == string::npos)
                s += ".0";
            return s;
        }
        case JsonType::Str:  return quote_str(v.get_str());
        case JsonType::Obj:
        case JsonType::Arr: {
            size_t len = 0;
            char* buf = yyjson_val_write(v.raw(), YYJSON_WRITE_PRETTY, &len);
            if (!buf) return "null";
            string result(buf, len);
            free(buf);
            return result;
        }
    }
    return "null";
}

string serialize_val_pretty(JsonVal v, int indent, int level) {
    if (!v.valid()) return "null";
    switch (v.type()) {
        case JsonType::Null: return "null";
        case JsonType::Bool: return v.get_bool() ? "true" : "false";
        case JsonType::Int:  return std::to_string(v.get_int());
        case JsonType::Real: {
            double d = v.get_real();
            if (std::isinf(d) || std::isnan(d)) return "null";
            std::ostringstream oss;
            oss << std::setprecision(17) << d;
            string s = oss.str();
            if (s.find('.') == string::npos && s.find('e') == string::npos)
                s += ".0";
            return s;
        }
        case JsonType::Str: return quote_str(v.get_str());
        case JsonType::Obj: {
            string cur_ind(indent * level, ' ');
            string next_ind(indent * (level + 1), ' ');

            struct KV { string key; JsonVal val; };
            vector<KV> kvs;
            auto it = v.obj_iter();
            JsonVal::ObjEntry e;
            while (it.next(e))
                kvs.push_back({string(e.key, e.key_len), e.val});
            std::sort(kvs.begin(), kvs.end(),
                      [](const KV& a, const KV& b) { return a.key < b.key; });

            if (kvs.empty()) return "{}";
            string r = "{\n";
            for (size_t i = 0; i < kvs.size(); ++i) {
                r += next_ind + quote_str(kvs[i].key.c_str()) + ": "
                   + serialize_val_pretty(kvs[i].val, indent, level + 1);
                if (i + 1 < kvs.size()) r += ',';
                r += '\n';
            }
            r += cur_ind + '}';
            return r;
        }
        case JsonType::Arr: {
            string cur_ind(indent * level, ' ');
            string next_ind(indent * (level + 1), ' ');

            vector<JsonVal> elems;
            auto it = v.arr_iter();
            JsonVal elem;
            while (it.next(elem)) elems.push_back(elem);

            if (elems.empty()) return "[]";
            string r = "[\n";
            for (size_t i = 0; i < elems.size(); ++i) {
                r += next_ind + serialize_val_pretty(elems[i], indent, level + 1);
                if (i + 1 < elems.size()) r += ',';
                r += '\n';
            }
            r += cur_ind + ']';
            return r;
        }
    }
    return "null";
}

string json_quote_str(const string& s) {
    return quote_str(s.c_str());
}

// ── val_equal ──

bool val_equal(JsonVal a, JsonVal b) {
    if (!a.valid() && !b.valid()) return true;
    if (!a.valid() || !b.valid()) return false;
    if (a.type() != b.type()) return false;
    switch (a.type()) {
        case JsonType::Null: return true;
        case JsonType::Bool: return a.get_bool() == b.get_bool();
        case JsonType::Int:  return a.get_int() == b.get_int();
        case JsonType::Real: return a.get_real() == b.get_real();
        case JsonType::Str:  return std::strcmp(a.get_str(), b.get_str()) == 0;
        default:             return false;
    }
}

// ── val_to_mut ──

MutVal val_to_mut(JsonVal v, MutVal ctx) {
    if (!v.valid()) return ctx.new_null();
    auto* copied = yyjson_val_mut_copy(ctx.raw_doc(), v.raw());
    return MutVal(ctx.raw_doc(), copied);
}

std::vector<JsonVal> collect_arr(JsonVal arr) {
    std::vector<JsonVal> result;
    auto it = arr.arr_iter();
    JsonVal elem;
    while (it.next(elem))
        result.push_back(elem);
    return result;
}

}  // namespace sultan
