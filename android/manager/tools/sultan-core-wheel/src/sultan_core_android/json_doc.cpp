#include "json_doc.h"
#include "json_cleaner.h"
#include "diag.h"
#include "resource_loader.h"
#include "yyjson.h"

#include <stdexcept>

namespace sultan {

static constexpr yyjson_read_flag kReadFlags =
    YYJSON_READ_ALLOW_TRAILING_COMMAS |
    YYJSON_READ_ALLOW_COMMENTS |
    YYJSON_READ_ALLOW_BOM;

// ── 生命周期 ──

JsonDoc::JsonDoc(yyjson_doc* doc) : doc_(doc) {}

JsonDoc::JsonDoc(JsonDoc&& other) noexcept : doc_(other.doc_) {
    other.doc_ = nullptr;
}

JsonDoc& JsonDoc::operator=(JsonDoc&& other) noexcept {
    if (this != &other) {
        yyjson_doc_free(doc_);
        doc_ = other.doc_;
        other.doc_ = nullptr;
    }
    return *this;
}

JsonDoc::~JsonDoc() {
    yyjson_doc_free(doc_);
}

// ── 内部解析 ──

static JsonDoc do_parse(const std::string& input) {
    yyjson_read_err err{};
    yyjson_doc* doc = yyjson_read_opts(
        const_cast<char*>(input.data()), input.size(),
        kReadFlags, nullptr, &err);

    if (!doc) {
        size_t line = 1;
        for (size_t i = 0; i < err.pos && i < input.size(); ++i) {
            if (input[i] == '\n') ++line;
        }
        std::string detail = err.msg ? err.msg : "unknown error";
        std::string what = "JSON parse error: " + detail
            + " (line: " + std::to_string(line)
            + ", pos: " + std::to_string(err.pos) + ")";
        throw JsonParseError(what, line, detail);
    }
    return JsonDoc::from_raw(doc);
}

// ── 工厂 ──

JsonDoc JsonDoc::parse(const std::string& text, bool clean) {
    if (!clean) {
        return do_parse(text);
    }
    auto result = clean_text(text);
    return do_parse(result.text);
}

JsonDoc JsonDoc::parse_file(const std::string& path, bool clean) {
    auto content = resource_loader().read_text(path);
    if (!clean) {
        return do_parse(*content);
    }
    auto result = clean_text(*content);
    if (!result.repairs.empty()) {
        std::string msg = path + ": 自动修复 " +
            std::to_string(result.repairs.size()) + " 处";
        for (auto& r : result.repairs) {
            msg += "\n  行 " + std::to_string(r.line) + ": " + r.desc;
        }
        diag_manager().info("json_repair", msg);
    }
    return do_parse(result.text);
}

// ── 序列化 ──

std::string JsonDoc::to_string(bool compact) const {
    if (!doc_)
        throw std::runtime_error("Cannot serialize null JsonDoc");

    yyjson_write_flag flg = compact
        ? YYJSON_WRITE_NOFLAG
        : YYJSON_WRITE_PRETTY;

    size_t len = 0;
    char* json = yyjson_write_opts(doc_, flg, nullptr, &len, nullptr);
    if (!json)
        throw std::runtime_error("JSON serialization failed");

    std::string result(json, len);
    free(json);
    return result;
}

// ── 内部访问 ──

JsonVal JsonDoc::root() const {
    return JsonVal(doc_ ? yyjson_doc_get_root(doc_) : nullptr);
}

JsonDoc JsonDoc::from_raw(yyjson_doc* doc) {
    return JsonDoc(doc);
}

}  // namespace sultan
