#include "modloader/resource_uri.h"

#include <algorithm>
#include <cctype>

namespace modloader {
namespace {

bool HasUnsafeSegment(std::string_view suffix) noexcept {
    std::size_t begin = 0;
    while (begin <= suffix.size()) {
        const std::size_t end = suffix.find('/', begin);
        const std::string_view segment = suffix.substr(
            begin, end == std::string_view::npos ? std::string_view::npos : end - begin);
        if (segment.empty() || segment == "." || segment == "..") {
            return true;
        }
        const auto percent = segment.find('%');
        if (percent != std::string_view::npos) {
            for (std::size_t index = percent; index + 2 < segment.size(); ++index) {
                if (segment[index] != '%') {
                    continue;
                }
                const char first = static_cast<char>(std::tolower(
                    static_cast<unsigned char>(segment[index + 1])));
                const char second = static_cast<char>(std::tolower(
                    static_cast<unsigned char>(segment[index + 2])));
                if ((first == '2' && (second == 'e' || second == 'f')) ||
                    (first == '5' && second == 'c')) {
                    return true;
                }
            }
        }
        if (end == std::string_view::npos) {
            return false;
        }
        begin = end + 1;
    }
    return true;
}

}  // namespace

std::optional<std::string> MakeOfficialResourceArgument(
    std::string_view absolute_path,
    std::string_view mod_root,
    ResourceArgumentMode mode) noexcept {
    if (absolute_path.empty() || mod_root.empty() || absolute_path.front() != '/' ||
        absolute_path.find('\0') != std::string_view::npos ||
        absolute_path.find('\\') != std::string_view::npos) {
        return std::nullopt;
    }

    while (mod_root.size() > 1 && mod_root.back() == '/') {
        mod_root.remove_suffix(1);
    }
    const std::string prefix = std::string(mod_root) + "/";
    if (absolute_path.size() <= prefix.size() ||
        absolute_path.substr(0, prefix.size()) != prefix ||
        HasUnsafeSegment(absolute_path.substr(prefix.size()))) {
        return std::nullopt;
    }

    if (mode == ResourceArgumentMode::kAbsolutePath) {
        return std::string(absolute_path);
    }
    return "file://" + std::string(absolute_path);
}

}  // namespace modloader
