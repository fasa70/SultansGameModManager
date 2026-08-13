#include "json_ops.h"
#include "json_doc.h"
#include "json_val.h"
#include "mut_doc.h"
#include "mut_val.h"

#include <cstring>

namespace sultan {

// ── 提取辅助（不可变树遍历） ──

static void collect_strings(JsonVal val, const char* name, size_t name_len,
                            std::vector<std::string>& out) {
    if (!val) return;

    if (val.is_obj()) {
        auto it = val.obj_iter();
        JsonVal::ObjEntry e;
        while (it.next(e)) {
            if (e.key_len == name_len && memcmp(e.key, name, name_len) == 0
                && e.val.is_str()) {
                out.emplace_back(e.val.get_str());
            }
            collect_strings(e.val, name, name_len, out);
        }
    } else if (val.is_arr()) {
        auto it = val.arr_iter();
        JsonVal elem;
        while (it.next(elem)) {
            collect_strings(elem, name, name_len, out);
        }
    }
}

static void collect_ints(JsonVal val, const char* name, size_t name_len,
                         std::vector<int64_t>& out) {
    if (!val) return;

    if (val.is_obj()) {
        auto it = val.obj_iter();
        JsonVal::ObjEntry e;
        while (it.next(e)) {
            if (e.key_len == name_len && memcmp(e.key, name, name_len) == 0
                && e.val.is_int()) {
                out.push_back(e.val.get_int());
            }
            collect_ints(e.val, name, name_len, out);
        }
    } else if (val.is_arr()) {
        auto it = val.arr_iter();
        JsonVal elem;
        while (it.next(elem)) {
            collect_ints(elem, name, name_len, out);
        }
    }
}

// ── 提取 API ──

std::vector<std::string> extract_string_values(
    const JsonDoc& doc, const std::string& field_name) {
    std::vector<std::string> result;
    collect_strings(doc.root(), field_name.c_str(), field_name.size(), result);
    return result;
}

std::vector<int64_t> extract_int_values(
    const JsonDoc& doc, const std::string& field_name) {
    std::vector<int64_t> result;
    collect_ints(doc.root(), field_name.c_str(), field_name.size(), result);
    return result;
}

std::vector<std::string> extract_root_keys(const JsonDoc& doc) {
    std::vector<std::string> result;
    auto root = doc.root();
    if (!root || !root.is_obj()) return result;

    result.reserve(root.obj_size());
    auto it = root.obj_iter();
    JsonVal::ObjEntry e;
    while (it.next(e)) {
        result.emplace_back(e.key, e.key_len);
    }
    return result;
}

std::unordered_map<std::string, int64_t>
extract_root_field_ints(const JsonDoc& doc, const std::string& field_name) {
    std::unordered_map<std::string, int64_t> result;
    auto root = doc.root();
    if (!root || !root.is_obj()) return result;

    auto it = root.obj_iter();
    JsonVal::ObjEntry e;
    while (it.next(e)) {
        if (!e.val.is_obj()) continue;
        auto child = e.val.obj_get(field_name.c_str());
        if (child.valid() && child.is_int()) {
            result.emplace(std::string(e.key, e.key_len), child.get_int());
        }
    }
    return result;
}

std::unordered_map<std::string, std::string>
extract_root_field_strs(const JsonDoc& doc, const std::string& field_name) {
    std::unordered_map<std::string, std::string> result;
    auto root = doc.root();
    if (!root || !root.is_obj()) return result;

    auto it = root.obj_iter();
    JsonVal::ObjEntry e;
    while (it.next(e)) {
        if (!e.val.is_obj()) continue;
        auto child = e.val.obj_get(field_name.c_str());
        if (child.valid() && child.is_str()) {
            result.emplace(std::string(e.key, e.key_len), std::string(child.get_str()));
        }
    }
    return result;
}

// ── 替换辅助（可变树遍历） ──

static void replace_field_ints_recursive(
    MutVal val,
    const char* name, size_t name_len,
    const std::unordered_map<int64_t, int64_t>& mapping) {
    if (!val) return;

    if (val.is_obj()) {
        auto it = val.obj_iter();
        MutVal::ObjEntry e;
        while (it.next(e)) {
            if (e.key_len == name_len && memcmp(e.key_str, name, name_len) == 0
                && e.val.is_int()) {
                auto found = mapping.find(e.val.get_int());
                if (found != mapping.end())
                    e.val.set_int(found->second);
            }
            replace_field_ints_recursive(e.val, name, name_len, mapping);
        }
    } else if (val.is_arr()) {
        auto it = val.arr_iter();
        MutVal elem;
        while (it.next(elem)) {
            replace_field_ints_recursive(elem, name, name_len, mapping);
        }
    }
}

static void replace_field_strs_recursive(
    MutVal val,
    const char* name, size_t name_len,
    const std::unordered_map<std::string, std::string>& mapping) {
    if (!val) return;

    if (val.is_obj()) {
        auto it = val.obj_iter();
        MutVal::ObjEntry e;
        while (it.next(e)) {
            if (e.key_len == name_len && memcmp(e.key_str, name, name_len) == 0
                && e.val.is_str()) {
                auto* s = e.val.get_str();
                if (s) {
                    auto found = mapping.find(s);
                    if (found != mapping.end())
                        e.val.set_str(found->second);
                }
            }
            replace_field_strs_recursive(e.val, name, name_len, mapping);
        }
    } else if (val.is_arr()) {
        auto it = val.arr_iter();
        MutVal elem;
        while (it.next(elem)) {
            replace_field_strs_recursive(elem, name, name_len, mapping);
        }
    }
}

// ── 替换 API ──

JsonDoc replace_field_ints(
    const JsonDoc& doc,
    const std::string& field_name,
    const std::unordered_map<int64_t, int64_t>& mapping) {
    auto d = MutDoc::from(doc);
    if (!mapping.empty())
        replace_field_ints_recursive(
            d.root(), field_name.c_str(), field_name.size(), mapping);
    return d.freeze();
}

JsonDoc replace_field_strs(
    const JsonDoc& doc,
    const std::string& field_name,
    const std::unordered_map<std::string, std::string>& mapping) {
    auto d = MutDoc::from(doc);
    if (!mapping.empty())
        replace_field_strs_recursive(
            d.root(), field_name.c_str(), field_name.size(), mapping);
    return d.freeze();
}

JsonDoc replace_root_keys(
    const JsonDoc& doc,
    const std::unordered_map<std::string, std::string>& mapping) {
    auto d = MutDoc::from(doc);
    if (!mapping.empty()) {
        auto root = d.root();
        if (root && root.is_obj()) {
            auto it = root.obj_iter();
            MutVal::ObjEntry e;
            while (it.next(e)) {
                if (e.key_str) {
                    auto found = mapping.find(e.key_str);
                    if (found != mapping.end())
                        e.key.set_str(found->second);
                }
            }
        }
    }
    return d.freeze();
}

// ── remap_all_ints：递归替换所有整数值 ──

static void remap_ints_recursive(
    MutVal val,
    const std::unordered_map<int64_t, int64_t>& mapping) {
    if (!val) return;

    if (val.is_obj()) {
        auto it = val.obj_iter();
        MutVal::ObjEntry e;
        while (it.next(e)) {
            if (e.val.is_int()) {
                auto found = mapping.find(e.val.get_int());
                if (found != mapping.end())
                    e.val.set_int(found->second);
            }
            remap_ints_recursive(e.val, mapping);
        }
    } else if (val.is_arr()) {
        auto it = val.arr_iter();
        MutVal elem;
        while (it.next(elem)) {
            if (elem.is_int()) {
                auto found = mapping.find(elem.get_int());
                if (found != mapping.end())
                    elem.set_int(found->second);
            }
            remap_ints_recursive(elem, mapping);
        }
    }
}

JsonDoc remap_all_ints(
    const JsonDoc& doc,
    const std::unordered_map<int64_t, int64_t>& mapping) {
    auto d = MutDoc::from(doc);
    if (!mapping.empty())
        remap_ints_recursive(d.root(), mapping);
    return d.freeze();
}

// ── remap_all_str_ids：替换所有字符串值和 key 中的 7 位数字 ID 子串 ──

static std::string replace_7digit_ids(
    const char* src, size_t len,
    const std::unordered_map<std::string, std::string>& mapping) {
    std::string result;
    result.reserve(len);
    size_t i = 0;
    while (i < len) {
        // 检查是否为 7 位数字的起始位置
        if (src[i] >= '0' && src[i] <= '9') {
            // 边界检查：前一个字符不能是数字
            bool left_boundary = (i == 0) || (src[i - 1] < '0' || src[i - 1] > '9');
            if (left_boundary) {
                // 计算连续数字长度
                size_t start = i;
                while (i < len && src[i] >= '0' && src[i] <= '9') ++i;
                size_t digit_len = i - start;
                // 右边界检查：下一个字符不能是数字（已经不是了，因为循环结束）
                if (digit_len == 7) {
                    std::string id_str(src + start, 7);
                    auto found = mapping.find(id_str);
                    if (found != mapping.end()) {
                        result += found->second;
                        continue;
                    }
                }
                result.append(src + start, digit_len);
                continue;
            }
        }
        result += src[i++];
    }
    return result;
}

static void remap_str_ids_recursive(
    MutVal val,
    const std::unordered_map<std::string, std::string>& mapping) {
    if (!val) return;

    if (val.is_obj()) {
        auto it = val.obj_iter();
        MutVal::ObjEntry e;
        while (it.next(e)) {
            // 替换 key 中的 ID
            if (e.key_str && e.key_len > 0) {
                auto new_key = replace_7digit_ids(e.key_str, e.key_len, mapping);
                if (new_key.size() != e.key_len ||
                    memcmp(new_key.c_str(), e.key_str, e.key_len) != 0) {
                    e.key.set_str(new_key);
                }
            }
            // 替换 str 值中的 ID
            if (e.val.is_str()) {
                auto* s = e.val.get_str();
                if (s) {
                    size_t slen = e.val.get_len();
                    auto new_val = replace_7digit_ids(s, slen, mapping);
                    if (new_val.size() != slen ||
                        memcmp(new_val.c_str(), s, slen) != 0) {
                        e.val.set_str(new_val);
                    }
                }
            }
            remap_str_ids_recursive(e.val, mapping);
        }
    } else if (val.is_arr()) {
        auto it = val.arr_iter();
        MutVal elem;
        while (it.next(elem)) {
            if (elem.is_str()) {
                auto* s = elem.get_str();
                if (s) {
                    size_t slen = elem.get_len();
                    auto new_val = replace_7digit_ids(s, slen, mapping);
                    if (new_val.size() != slen ||
                        memcmp(new_val.c_str(), s, slen) != 0) {
                        elem.set_str(new_val);
                    }
                }
            }
            remap_str_ids_recursive(elem, mapping);
        }
    }
}

JsonDoc remap_all_str_ids(
    const JsonDoc& doc,
    const std::unordered_map<std::string, std::string>& mapping) {
    auto d = MutDoc::from(doc);
    if (!mapping.empty())
        remap_str_ids_recursive(d.root(), mapping);
    return d.freeze();
}

// ── 分类 API ──

std::string classify_json(const JsonDoc& doc) {
    auto root = doc.root();
    if (!root || !root.is_obj()) return "config";

    if (root.obj_get("id").valid()) return "entity";

    auto it = root.obj_iter();
    JsonVal::ObjEntry e;
    bool all_obj = true;
    bool any_has_id = false;
    bool has_values = false;

    while (it.next(e)) {
        has_values = true;
        if (!e.val.is_obj()) { all_obj = false; break; }
        if (e.val.obj_get("id").valid()) any_has_id = true;
    }

    if (has_values && all_obj && any_has_id) return "dictionary";
    return "config";
}

}  // namespace sultan
