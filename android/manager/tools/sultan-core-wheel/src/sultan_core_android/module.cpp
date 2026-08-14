#include <nanobind/nanobind.h>
#include <nanobind/stl/string.h>
#include <nanobind/stl/unordered_map.h>
#include <nanobind/stl/vector.h>

#include "json_doc.h"
#include "json_ops.h"
#include "overlay.h"

namespace nb = nanobind;
using namespace sultan;

NB_MODULE(_native, m) {
    m.doc() = "Sultan's Game Manager JSON operations";

    nb::class_<JsonDoc>(m, "JsonDoc")
        .def_static(
            "parse",
            [](const std::string& text, bool clean) {
                return JsonDoc::parse(text, clean);
            },
            nb::arg("text"), nb::arg("clean") = true)
        .def_static(
            "parse_file",
            [](const std::string& path, bool clean) {
                return JsonDoc::parse_file(path, clean);
            },
            nb::arg("path"), nb::arg("clean") = true)
        .def("to_string", &JsonDoc::to_string, nb::arg("compact") = false)
        .def("valid", &JsonDoc::valid);

    auto json_ops = m.def_submodule("json_ops");
    json_ops.def("extract_string_values", &extract_string_values,
                 nb::arg("doc"), nb::arg("field_name"));
    json_ops.def("extract_int_values", &extract_int_values,
                 nb::arg("doc"), nb::arg("field_name"));
    json_ops.def("extract_root_keys", &extract_root_keys, nb::arg("doc"));
    json_ops.def("extract_root_field_ints", &extract_root_field_ints,
                 nb::arg("doc"), nb::arg("field_name"));
    json_ops.def("extract_root_field_strs", &extract_root_field_strs,
                 nb::arg("doc"), nb::arg("field_name"));
    json_ops.def("replace_field_ints", &replace_field_ints,
                 nb::arg("doc"), nb::arg("field_name"), nb::arg("mapping"));
    json_ops.def("replace_field_strs", &replace_field_strs,
                 nb::arg("doc"), nb::arg("field_name"), nb::arg("mapping"));
    json_ops.def("replace_root_keys", &replace_root_keys,
                 nb::arg("doc"), nb::arg("mapping"));
    json_ops.def("remap_all_ints", &remap_all_ints,
                 nb::arg("doc"), nb::arg("mapping"));
    json_ops.def("remap_all_str_ids", &remap_all_str_ids,
                 nb::arg("doc"), nb::arg("mapping"));
    json_ops.def("classify_json", &classify_json, nb::arg("doc"));

    m.def("overlay_json", &overlay_json_documents,
          nb::arg("documents"),
          "Merge ordered Mod JSON texts without a game base.");
}
