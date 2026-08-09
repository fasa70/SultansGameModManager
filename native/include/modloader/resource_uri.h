#pragma once

#include <optional>
#include <string>
#include <string_view>

namespace modloader {

enum class ResourceArgumentMode {
    kFileUri,
    kAbsolutePath,
};

// Accept only a canonical resource beneath mod_root.  The returned string is
// intentionally not percent encoded: that matches the verified Unity loader
// behavior for this game profile.
std::optional<std::string> MakeOfficialResourceArgument(
    std::string_view absolute_path,
    std::string_view mod_root,
    ResourceArgumentMode mode) noexcept;

}  // namespace modloader
