#pragma once
#include "mut_val.h"

struct yyjson_mut_doc;

namespace sultan {

class JsonDoc;

class MutDoc {
    yyjson_mut_doc* doc_ = nullptr;
public:
    MutDoc();
    ~MutDoc();
    MutDoc(MutDoc&&) noexcept;
    MutDoc& operator=(MutDoc&&) noexcept;
    MutDoc(const MutDoc&) = delete;
    MutDoc& operator=(const MutDoc&) = delete;

    static MutDoc from(const JsonDoc& doc);

    MutVal root();
    void set_root(MutVal root);
    JsonDoc freeze();
    yyjson_mut_doc* raw() const { return doc_; }
};

}  // namespace sultan
