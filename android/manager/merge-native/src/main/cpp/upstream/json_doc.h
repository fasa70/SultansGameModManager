#pragma once
#include "json_val.h"
#include <stdexcept>
#include <string>
#include <vector>

struct yyjson_doc;

namespace sultan {

class BatchHandle;

struct JsonParseError : std::runtime_error {
    size_t line;
    std::string detail;
    JsonParseError(const std::string& what, size_t ln, std::string det)
        : std::runtime_error(what), line(ln), detail(std::move(det)) {}
};

class JsonDoc {
public:
    JsonDoc(const JsonDoc&) = delete;
    JsonDoc& operator=(const JsonDoc&) = delete;
    JsonDoc(JsonDoc&& other) noexcept;
    JsonDoc& operator=(JsonDoc&& other) noexcept;
    ~JsonDoc();

    // ── 工厂 ──

    // 从文本解析。clean=true 时先执行 clean_text 修复非标准语法
    static JsonDoc parse(const std::string& text, bool clean = true);

    // 从文件解析。通过 ResourceLoader 读取（BOM 已剥离），含 BOM 诊断
    static JsonDoc parse_file(const std::string& path, bool clean = true);

    // ── 序列化 ──

    // compact=false: 4 空格缩进  compact=true: 紧凑输出
    std::string to_string(bool compact = false) const;

    // ── 内部访问（供 C++ 模块使用） ──

    bool valid() const { return doc_ != nullptr; }
    JsonVal root() const;
    yyjson_doc* raw_doc() const { return doc_; }

    static JsonDoc from_raw(yyjson_doc* doc);

    static BatchHandle* batch_parse_files(
        const std::vector<std::string>& paths, bool async = true);

    JsonDoc() = default;

private:
    explicit JsonDoc(yyjson_doc* doc);

    yyjson_doc* doc_ = nullptr;
};

}  // namespace sultan
