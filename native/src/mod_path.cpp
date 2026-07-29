#include "modloader/mod_path.h"

#include <algorithm>
#include <cctype>

namespace modloader {

std::optional<std::string> BuildModRoot(std::string_view external_files_dir) {
    const bool has_non_space = std::any_of(
        external_files_dir.begin(), external_files_dir.end(), [](unsigned char ch) {
            return std::isspace(ch) == 0;
        });
    if (!has_non_space) {
        return std::nullopt;
    }

    std::string root(external_files_dir);
    while (root.size() > 1 && root.back() == '/') {
        root.pop_back();
    }
    root += "/Mod";
    return root;
}

}  // namespace modloader
