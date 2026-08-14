#pragma once
#include "state_node.h"
#include "state_formatter.h"
#include <string>
#include <memory>

namespace sultan {

using std::string;
using std::unique_ptr;

class JsonDoc;

class JsonState {
public:
    JsonState() = default;
    JsonState(JsonState&&) noexcept = default;
    JsonState& operator=(JsonState&&) noexcept = default;
    
    JsonState(const JsonState&) = delete;
    JsonState& operator=(const JsonState&) = delete;

    // JsonVal 引用 JsonDoc 数据，调用方需保证 doc 生命周期覆盖返回的 JsonState 使用期
    static JsonState from_doc(const JsonDoc& doc);
    static JsonState from_node(StateNodePtr root);

    JsonDoc to_doc() const;
    FormatResult format(int highlight_version) const;
    JsonState clone() const;

    StateBase& root();
    const StateBase& root() const;
    bool valid() const { return root_ != nullptr; }

private:
    explicit JsonState(StateNodePtr root);
    StateNodePtr root_;
};

}  // namespace sultan
