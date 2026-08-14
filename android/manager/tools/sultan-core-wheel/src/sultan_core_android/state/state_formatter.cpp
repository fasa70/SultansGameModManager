#include "state_formatter.h"
#include "state_node.h"
#include <algorithm>
#include <sstream>

namespace sultan {

using std::string;
using std::vector;
using std::pair;

static constexpr int INDENT = 4;

// ── 序列化 StateBase → 纯 JSON 文本（无 ChangeKind 追踪）──

static string serialize_node(const StateBase& node, int indent, int level,
                             bool ignore_deleted = false);

static string json_encode_key(const string& key) {
    return json_quote_str(key);
}

static string serialize_element(const JsonElementState& elem, int indent, int level,
                                bool use_old = false) {
    return serialize_val_pretty(use_old ? elem.old_value : elem.value, indent, level);
}

static string serialize_dict(const JsonDictState& dict, int indent, int level,
                             bool ignore_deleted = false) {
    string ind(indent, ' ');
    string current_ind(indent * level, ' ');
    string next_ind(indent * (level + 1), ' ');

    vector<string> parts;
    for (auto& [key, entry] : dict.entries) {
        if (!entry) continue;
        if (!ignore_deleted && is_deleted(base_kind(entry->kind()))) continue;
        string key_str = json_encode_key(key);

        if (entry->is_array() && entry->as_array().is_duplist) {
            auto& arr = entry->as_array();
            std::unordered_map<int, size_t> id_to_idx;
            for (size_t i = 0; i < arr.indices.size(); ++i)
                id_to_idx[arr.indices[i]] = i;
            for (int eid : arr.order) {
                if (eid == 0 || eid == -1) continue;
                auto idx_it = id_to_idx.find(eid);
                if (idx_it == id_to_idx.end()) continue;
                auto& elem = arr.diffs[idx_it->second];
                if (!elem || (!ignore_deleted && is_deleted(base_kind(elem->kind())))) continue;
                parts.push_back(next_ind + key_str + ": " +
                                serialize_node(*elem, indent, level + 1, ignore_deleted));
            }
        } else {
            parts.push_back(next_ind + key_str + ": " +
                            serialize_node(*entry, indent, level + 1, ignore_deleted));
        }
    }

    if (parts.empty()) return "{}";
    string result = "{\n";
    for (size_t i = 0; i < parts.size(); ++i) {
        result += parts[i];
        if (i + 1 < parts.size()) result += ',';
        result += '\n';
    }
    result += current_ind + '}';
    return result;
}

static string serialize_array(const JsonArrayState& arr, int indent, int level,
                              bool ignore_deleted = false) {
    string current_ind(indent * level, ' ');
    string next_ind(indent * (level + 1), ' ');

    std::unordered_map<int, size_t> id_to_idx;
    for (size_t i = 0; i < arr.indices.size(); ++i)
        id_to_idx[arr.indices[i]] = i;

    vector<string> parts;
    for (int eid : arr.order) {
        if (eid == 0 || eid == -1) continue;
        auto it = id_to_idx.find(eid);
        if (it == id_to_idx.end()) continue;
        auto& elem = arr.diffs[it->second];
        if (!elem || (!ignore_deleted && is_deleted(base_kind(elem->kind())))) continue;
        parts.push_back(next_ind + serialize_node(*elem, indent, level + 1, ignore_deleted));
    }

    if (parts.empty()) return "[]";
    string result = "[\n";
    for (size_t i = 0; i < parts.size(); ++i) {
        result += parts[i];
        if (i + 1 < parts.size()) result += ',';
        result += '\n';
    }
    result += current_ind + ']';
    return result;
}

static string serialize_node(const StateBase& node, int indent, int level,
                             bool ignore_deleted) {
    if (node.is_element())
        return serialize_element(node.as_element(), indent, level, ignore_deleted);
    if (node.is_dict())
        return serialize_dict(node.as_dict(), indent, level, ignore_deleted);
    return serialize_array(node.as_array(), indent, level, ignore_deleted);
}

// ── 格式化辅助函数 ──

enum class Side { Both, Left, Right };

static void emit(
    const string& text, FormatResult& r,
    int8_t kind_val = static_cast<int8_t>(ChangeKind::Origin),
    Side side = Side::Both
) {
    bool show_left = (side != Side::Right);
    bool show_right = (side != Side::Left);
    int8_t lk = show_left ? kind_val : -1;
    int8_t rk = show_right ? kind_val : -1;

    // 按 \n 分割
    size_t start = 0;
    while (true) {
        size_t pos = text.find('\n', start);
        string line = (pos == string::npos) ? text.substr(start) : text.substr(start, pos - start);
        r.left_lines.push_back(show_left ? line : "");
        r.right_lines.push_back(show_right ? line : "");
        r.left_kinds.push_back(lk);
        r.right_kinds.push_back(rk);
        if (pos == string::npos) break;
        start = pos + 1;
    }
}

static void emit_changed(
    const string& old_text, const string& new_text,
    FormatResult& r, int8_t kind_val
) {
    vector<string> old_split, new_split;
    {
        size_t start = 0;
        while (true) {
            size_t pos = old_text.find('\n', start);
            old_split.push_back(pos == string::npos ? old_text.substr(start) : old_text.substr(start, pos - start));
            if (pos == string::npos) break;
            start = pos + 1;
        }
    }
    {
        size_t start = 0;
        while (true) {
            size_t pos = new_text.find('\n', start);
            new_split.push_back(pos == string::npos ? new_text.substr(start) : new_text.substr(start, pos - start));
            if (pos == string::npos) break;
            start = pos + 1;
        }
    }

    size_t max_len = std::max(old_split.size(), new_split.size());
    for (size_t i = 0; i < max_len; ++i) {
        if (i < old_split.size()) {
            r.left_lines.push_back(old_split[i]);
            r.left_kinds.push_back(kind_val);
        } else {
            r.left_lines.push_back("");
            r.left_kinds.push_back(-1);
        }
        if (i < new_split.size()) {
            r.right_lines.push_back(new_split[i]);
            r.right_kinds.push_back(kind_val);
        } else {
            r.right_lines.push_back("");
            r.right_kinds.push_back(-1);
        }
    }
}

struct FieldKindResult {
    ChangeKind kind;
    bool is_current;
};

static FieldKindResult get_field_kind(const StateBase& entry, int highlight_version) {
    if (entry.is_element()) {
        auto& elem = entry.as_element();
        if (elem.version == highlight_version && !is_origin(elem.kind_))
            return {elem.kind_, true};
        return {ChangeKind::Origin, false};
    }
    return {ChangeKind::Origin, false};
}

static vector<pair<string, const StateBase*>> collect_dict_entries(
    const JsonDictState& dd, int highlight_version
) {
    vector<pair<string, const StateBase*>> result;
    for (auto& [key, entry] : dd.entries) {
        if (!entry) continue;
        if (entry->is_element()) {
            auto& elem = entry->as_element();
            if (is_deleted(elem.kind_) && elem.version != highlight_version)
                continue;
        } else if (entry->is_dict()) {
            auto& dict = entry->as_dict();
            if (is_deleted(dict.kind_) && dict.version != highlight_version)
                continue;
        } else if (entry->is_array()) {
            auto& arr = entry->as_array();
            if (is_deleted(arr.kind_) && arr.version != highlight_version)
                continue;
        }
        result.emplace_back(key, entry.get());
    }
    return result;
}

static vector<pair<int, const StateBase*>> collect_array_elements(
    const JsonArrayState& afd, int highlight_version
) {
    std::unordered_map<int, size_t> id_to_idx;
    for (size_t i = 0; i < afd.indices.size(); ++i)
        id_to_idx[afd.indices[i]] = i;

    vector<pair<int, const StateBase*>> result;
    for (int eid : afd.order) {
        if (eid == 0 || eid == -1) continue;
        auto it = id_to_idx.find(eid);
        if (it == id_to_idx.end()) continue;
        auto& diff = afd.diffs[it->second];
        if (!diff) continue;
        if (diff->is_element()) {
            auto& elem = diff->as_element();
            if (is_deleted(elem.kind_) && elem.version != highlight_version)
                continue;
        } else if (diff->is_dict()) {
            auto& dict = diff->as_dict();
            if (is_deleted(dict.kind_) && dict.version != highlight_version)
                continue;
        } else if (diff->is_array()) {
            auto& arr = diff->as_array();
            if (is_deleted(arr.kind_) && arr.version != highlight_version)
                continue;
        }
        result.emplace_back(eid, diff.get());
    }
    return result;
}

// 前向声明
static void format_entry(
    const StateBase& entry, const string& prefix, const string& comma,
    int highlight_version, int level, FormatResult& r);
static void format_dict(
    const JsonDictState& dd, int highlight_version, int level,
    FormatResult& r, bool is_root);
static void format_array(
    const JsonArrayState& afd, int highlight_version, int level,
    FormatResult& r);
static void format_duplist(
    const string& key_str, const JsonArrayState& afd,
    int highlight_version, int level, const string& trailing_comma,
    FormatResult& r);

static void format_entry(
    const StateBase& entry, const string& prefix, const string& comma,
    int highlight_version, int level, FormatResult& r
) {
    if (entry.is_element()) {
        auto [kind, is_current] = get_field_kind(entry, highlight_version);
        ChangeKind dk = is_current ? entry.as_element().kind_ : ChangeKind::Origin;

        if (is_origin(dk)) {
            string val_str = serialize_element(entry.as_element(), INDENT, level);
            emit(prefix + val_str + comma, r);
        } else {
            auto& elem = entry.as_element();
            bool has_old = elem.old_value.valid();
            bool has_new = elem.value.valid();

            if (has_old && has_new && !val_equal(elem.old_value, elem.value)) {
                int8_t hl = static_cast<int8_t>(ChangeKind::Changed | change_flags(kind));
                string old_str = serialize_val_pretty(elem.old_value, INDENT, level);
                string new_str = serialize_val_pretty(elem.value, INDENT, level);
                emit_changed(prefix + old_str + comma, prefix + new_str + comma, r, hl);
            } else if (has_new && !has_old) {
                int8_t hl = static_cast<int8_t>(ChangeKind::Added | change_flags(kind));
                string val_str = serialize_val_pretty(elem.value, INDENT, level);
                emit(prefix + val_str + comma, r, hl, Side::Right);
            } else if (has_old && !has_new) {
                int8_t hl = static_cast<int8_t>(ChangeKind::Deleted | change_flags(kind));
                string old_str = serialize_val_pretty(elem.old_value, INDENT, level);
                emit(prefix + old_str + comma, r, hl, Side::Left);
            } else {
                auto& v = has_new ? elem.value : elem.old_value;
                string val_str = serialize_val_pretty(v, INDENT, level);
                emit(prefix + val_str + comma, r);
            }
        }
    } else {
        ChangeKind ck = entry.kind();
        int ver = entry.is_dict() ? entry.as_dict().version : entry.as_array().version;

        if (ver == highlight_version && is_added(ck)) {
            int8_t hl = static_cast<int8_t>(ChangeKind::Added | change_flags(ck));
            string val_str = serialize_node(entry, INDENT, level);
            emit(prefix + val_str + comma, r, hl, Side::Right);
        } else if (ver == highlight_version && is_deleted(ck)) {
            int8_t hl = static_cast<int8_t>(ChangeKind::Deleted | change_flags(ck));
            string val_str = serialize_node(entry, INDENT, level, true);
            emit(prefix + val_str + comma, r, hl, Side::Left);
        } else {
            FormatResult sub;
            if (entry.is_dict()) {
                format_dict(entry.as_dict(), highlight_version, level, sub, false);
            } else {
                format_array(entry.as_array(), highlight_version, level, sub);
            }
            if (!sub.left_lines.empty()) {
                sub.left_lines[0] = prefix + sub.left_lines[0];
                sub.right_lines[0] = prefix + sub.right_lines[0];
            }
            if (!sub.left_lines.empty() && !comma.empty()) {
                sub.left_lines.back() += comma;
                sub.right_lines.back() += comma;
            }
            r.left_lines.insert(r.left_lines.end(), sub.left_lines.begin(), sub.left_lines.end());
            r.right_lines.insert(r.right_lines.end(), sub.right_lines.begin(), sub.right_lines.end());
            r.left_kinds.insert(r.left_kinds.end(), sub.left_kinds.begin(), sub.left_kinds.end());
            r.right_kinds.insert(r.right_kinds.end(), sub.right_kinds.begin(), sub.right_kinds.end());
        }
    }
}

static void format_dict(
    const JsonDictState& dd, int highlight_version, int level,
    FormatResult& r, bool is_root
) {
    string current_ind(INDENT * level, ' ');
    string next_ind(INDENT * (level + 1), ' ');

    auto entries = collect_dict_entries(dd, highlight_version);

    if (entries.empty() && !is_root) {
        emit("{}", r);
        return;
    }
    emit("{", r);

    for (size_t idx = 0; idx < entries.size(); ++idx) {
        auto& [key, entry] = entries[idx];
        string key_str = json_encode_key(key);
        string comma = (idx + 1 < entries.size()) ? "," : "";

        if (entry->is_array() && entry->as_array().is_duplist) {
            format_duplist(key_str, entry->as_array(), highlight_version,
                           level, comma, r);
        } else {
            format_entry(*entry, next_ind + key_str + ": ", comma,
                         highlight_version, level + 1, r);
        }
    }

    emit(current_ind + "}", r);
}

static void format_array(
    const JsonArrayState& afd, int highlight_version, int level,
    FormatResult& r
) {
    string current_ind(INDENT * level, ' ');
    string next_ind(INDENT * (level + 1), ' ');

    auto elements = collect_array_elements(afd, highlight_version);
    if (elements.empty()) {
        emit("[]", r);
        return;
    }
    emit("[", r);

    for (size_t idx = 0; idx < elements.size(); ++idx) {
        auto& [eid, entry] = elements[idx];
        string comma = (idx + 1 < elements.size()) ? "," : "";
        format_entry(*entry, next_ind, comma, highlight_version, level + 1, r);
    }

    emit(current_ind + "]", r);
}

static void format_duplist(
    const string& key_str, const JsonArrayState& afd,
    int highlight_version, int level, const string& trailing_comma,
    FormatResult& r
) {
    string next_ind(INDENT * (level + 1), ' ');
    auto elements = collect_array_elements(afd, highlight_version);

    for (size_t idx = 0; idx < elements.size(); ++idx) {
        auto& [eid, entry] = elements[idx];
        string comma = (idx == elements.size() - 1) ? trailing_comma : ",";
        format_entry(*entry, next_ind + key_str + ": ", comma,
                     highlight_version, level + 1, r);
    }
}

// ── 入口 ──

FormatResult format_state(const StateBase& root, int highlight_version) {
    FormatResult r;
    if (root.is_dict()) {
        format_dict(root.as_dict(), highlight_version, 0, r, true);
    } else if (root.is_array()) {
        format_array(root.as_array(), highlight_version, 0, r);
    } else {
        string val = serialize_element(root.as_element(), INDENT, 0);
        emit(val, r);
    }
    return r;
}

}  // namespace sultan
