#include "overlay.h"

#include "apply_delta.h"
#include "compute_delta.h"
#include "json_doc.h"
#include "json_state.h"

#include <algorithm>
#include <stdexcept>

namespace sultan {

JsonDoc overlay_json_documents(const std::vector<std::string>& texts) {
  if (texts.empty())
    throw std::runtime_error("overlay requires at least one JSON document");

  std::vector<JsonDoc> docs;
  docs.reserve(texts.size());
  for (const auto& text : texts)
    docs.push_back(JsonDoc::parse(text, true));

  for (const auto& doc : docs) {
    if (!doc.valid())
      throw std::runtime_error("overlay contains an invalid JSON document");
  }

  // The upstream State/Delta applicator requires a dictionary root. For other
  // valid JSON roots, retain the highest-priority document rather than turning
  // a valid Mod file into a hard merge failure.
  if (std::any_of(docs.begin(), docs.end(), [](const JsonDoc& doc) {
        return !doc.root().is_obj();
      })) {
    return JsonDoc::parse(docs.back().to_string(), false);
  }

  JsonState state = JsonState::from_doc(docs.front());
  for (size_t i = 1; i < docs.size(); ++i) {
    JsonDoc current = state.to_doc();
    auto delta = compute_delta(
        current, docs[i], MergeMode::Smart, true);
    if (!delta)
      continue;
    if (delta->type() != DeltaType::Dict)
      throw std::runtime_error("overlay root delta must be a JSON object");
    apply_delta_to_state(
        state, delta->as_dict(), nullptr, static_cast<int>(i), false);
  }

  return state.to_doc();
}

}  // namespace sultan
