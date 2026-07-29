#pragma once

#include <optional>
#include <string>
#include <string_view>

namespace modloader {

std::optional<std::string> BuildModRoot(std::string_view external_files_dir);

}  // namespace modloader
