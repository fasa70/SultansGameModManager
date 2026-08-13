#include "mut_doc.h"
#include "json_doc.h"
#include "yyjson.h"
#include <stdexcept>

namespace sultan {

MutDoc::MutDoc() : doc_(yyjson_mut_doc_new(nullptr)) {
    if (!doc_) throw std::runtime_error("MutDoc: failed to create mut doc");
}

MutDoc::~MutDoc() {
    if (doc_) yyjson_mut_doc_free(doc_);
}

MutDoc::MutDoc(MutDoc&& o) noexcept : doc_(o.doc_) {
    o.doc_ = nullptr;
}

MutDoc& MutDoc::operator=(MutDoc&& o) noexcept {
    if (this != &o) {
        if (doc_) yyjson_mut_doc_free(doc_);
        doc_ = o.doc_;
        o.doc_ = nullptr;
    }
    return *this;
}

MutDoc MutDoc::from(const JsonDoc& doc) {
    MutDoc d;
    if (d.doc_) yyjson_mut_doc_free(d.doc_);
    d.doc_ = yyjson_doc_mut_copy(doc.raw_doc(), nullptr);
    if (!d.doc_) throw std::runtime_error("MutDoc::from: failed to copy doc");
    return d;
}

MutVal MutDoc::root() {
    return MutVal(doc_, doc_ ? yyjson_mut_doc_get_root(doc_) : nullptr);
}

void MutDoc::set_root(MutVal root) {
    if (doc_) yyjson_mut_doc_set_root(doc_, root.raw());
}

JsonDoc MutDoc::freeze() {
    if (!doc_) throw std::runtime_error("MutDoc::freeze: null doc");
    auto* idoc = yyjson_mut_doc_imut_copy(doc_, nullptr);
    yyjson_mut_doc_free(doc_);
    doc_ = nullptr;
    if (!idoc) throw std::runtime_error("MutDoc::freeze: imut_copy failed");
    return JsonDoc::from_raw(idoc);
}

}  // namespace sultan
