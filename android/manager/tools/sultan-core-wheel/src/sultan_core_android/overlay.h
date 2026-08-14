#pragma once

#include <string>
#include <vector>

namespace sultan {

class JsonDoc;

/**
 * Merge ordered Mod JSON documents without a game base.
 *
 * The first valid document is the initial overlay state. Later documents are
 * applied with upstream State/Delta semantics while suppressing deletion.
 */
JsonDoc overlay_json_documents(const std::vector<std::string>& texts);

}  // namespace sultan
