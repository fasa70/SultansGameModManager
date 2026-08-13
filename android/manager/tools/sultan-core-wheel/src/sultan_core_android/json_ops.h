#pragma once
#include <cstdint>
#include <string>
#include <unordered_map>
#include <vector>

namespace sultan {

class JsonDoc;

// ── 提取 ──

// 递归查找所有 key == field_name 的字符串类型字段值
std::vector<std::string> extract_string_values(
    const JsonDoc& doc, const std::string& field_name);

// 递归查找所有 key == field_name 的整数类型字段值
std::vector<int64_t> extract_int_values(
    const JsonDoc& doc, const std::string& field_name);

// 提取根级对象的所有键（不递归）
std::vector<std::string> extract_root_keys(const JsonDoc& doc);

// 根对象中每个 entry（值为 object），提取其直接子字段的整数值
// 返回 {root_key: int_value}，跳过不存在或非整数的
std::unordered_map<std::string, int64_t>
extract_root_field_ints(const JsonDoc& doc, const std::string& field_name);

// 同上，字符串版
std::unordered_map<std::string, std::string>
extract_root_field_strs(const JsonDoc& doc, const std::string& field_name);

// ── 替换（返回新 JsonDoc，原文档不变） ──

// 递归查找所有 key == field_name 的字段，替换其整数值
JsonDoc replace_field_ints(
    const JsonDoc& doc,
    const std::string& field_name,
    const std::unordered_map<int64_t, int64_t>& mapping);

// 递归查找所有 key == field_name 的字段，替换其字符串值（精确匹配）
JsonDoc replace_field_strs(
    const JsonDoc& doc,
    const std::string& field_name,
    const std::unordered_map<std::string, std::string>& mapping);

// 仅替换根级对象键（不递归）
JsonDoc replace_root_keys(
    const JsonDoc& doc,
    const std::unordered_map<std::string, std::string>& mapping);

// ── ID 重映射（返回新 JsonDoc，原文档不变） ──

// 递归替换所有整数值（不限字段名）
JsonDoc remap_all_ints(
    const JsonDoc& doc,
    const std::unordered_map<int64_t, int64_t>& mapping);

// 递归替换所有字符串值和所有 key 中的 7 位数字 ID 子串
JsonDoc remap_all_str_ids(
    const JsonDoc& doc,
    const std::unordered_map<std::string, std::string>& mapping);

// ── 分类 ──

// 判定 JSON 文件类型："dictionary" | "entity" | "config"
std::string classify_json(const JsonDoc& doc);

}  // namespace sultan
