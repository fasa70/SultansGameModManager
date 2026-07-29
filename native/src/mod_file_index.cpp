#include "modloader/mod_file_index.h"

#include <algorithm>
#include <array>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#if defined(_WIN32)
#define lstat stat
#ifndef S_ISLNK
#define S_ISLNK(mode) 0
#endif
#ifndef O_CLOEXEC
#define O_CLOEXEC 0
#endif
#ifndef O_NOFOLLOW
#define O_NOFOLLOW 0
#endif
#endif

namespace modloader {
namespace {

constexpr std::size_t kMaximumConfigFileSize = 16U * 1024U * 1024U;
constexpr std::size_t kMaximumMediaFileSize = 256U * 1024U * 1024U;
constexpr std::size_t kMaximumPathDepth = 8;

bool IsSafePathComponent(std::string_view name) {
    if (name.empty() || name == "." || name == "..") {
        return false;
    }
    return std::none_of(name.begin(), name.end(), [](unsigned char character) {
        return character == '/' || character == '\\' || character == '\0';
    });
}

bool HasSuffix(std::string_view path, std::string_view suffix) {
    return path.size() >= suffix.size() &&
        path.compare(path.size() - suffix.size(), suffix.size(), suffix) == 0;
}

bool IsRegularFile(const struct stat& status, std::size_t maximum_size) {
    return S_ISREG(status.st_mode) && status.st_size >= 0 &&
        static_cast<std::uint64_t>(status.st_size) <=
            static_cast<std::uint64_t>(maximum_size);
}

bool HasSuffixInsensitive(std::string_view path, std::string_view suffix) {
    if (path.size() < suffix.size()) {
        return false;
    }
    const std::size_t start = path.size() - suffix.size();
    for (std::size_t index = 0; index < suffix.size(); ++index) {
        const unsigned char value = static_cast<unsigned char>(path[start + index]);
        const unsigned char expected = static_cast<unsigned char>(suffix[index]);
        const unsigned char normalized = value >= 'A' && value <= 'Z' ?
            static_cast<unsigned char>(value + ('a' - 'A')) : value;
        if (normalized != expected) {
            return false;
        }
    }
    return true;
}

bool HasSupportedMediaSuffix(std::string_view relative) {
    return HasSuffixInsensitive(relative, ".png") || HasSuffixInsensitive(relative, ".wav") ||
        HasSuffixInsensitive(relative, ".mp3") || HasSuffixInsensitive(relative, ".ogg");
}

void AddFile(IndexedMod* mod, const std::string& root, const std::string& relative,
             const struct stat& status) {
    IndexedModFile file{relative, root + "/" + relative,
                        static_cast<std::size_t>(status.st_size)};
    if (HasSuffix(relative, ".json")) {
        mod->config_files.push_back(std::move(file));
    } else if (HasSuffixInsensitive(relative, ".png")) {
        mod->image_files.push_back(std::move(file));
    } else if (HasSuffixInsensitive(relative, ".wav") || HasSuffixInsensitive(relative, ".mp3") ||
               HasSuffixInsensitive(relative, ".ogg")) {
        mod->audio_files.push_back(std::move(file));
    }
}

void ScanDirectory(IndexedMod* mod, const std::string& root, const std::string& relative,
                   std::size_t depth, std::vector<std::string>* rejected_entries) {
    if (depth > kMaximumPathDepth) {
        rejected_entries->push_back(root + "/" + relative + ":depth");
        return;
    }
    const std::string path = relative.empty() ? root : root + "/" + relative;
    DIR* directory = opendir(path.c_str());
    if (directory == nullptr) {
        return;
    }

    while (dirent* entry = readdir(directory)) {
        const std::string_view name(entry->d_name);
        if (!IsSafePathComponent(name)) {
            if (name != "." && name != "..") {
                rejected_entries->push_back(path + "/" + std::string(name) + ":name");
            }
            continue;
        }
        const std::string child_relative = relative.empty()
            ? std::string(name)
            : relative + "/" + std::string(name);
        const std::string child_path = root + "/" + child_relative;
        struct stat status {};
        if (lstat(child_path.c_str(), &status) != 0) {
            rejected_entries->push_back(child_path + ":stat");
            continue;
        }
        if (S_ISLNK(status.st_mode)) {
            rejected_entries->push_back(child_path + ":symlink");
            continue;
        }
        if (S_ISDIR(status.st_mode)) {
            ScanDirectory(mod, root, child_relative, depth + 1, rejected_entries);
        } else if (S_ISREG(status.st_mode)) {
            const std::size_t maximum_size = HasSupportedMediaSuffix(child_relative) ?
                kMaximumMediaFileSize : kMaximumConfigFileSize;
            if (IsRegularFile(status, maximum_size)) {
                AddFile(mod, root, child_relative, status);
            } else {
                rejected_entries->push_back(child_path + ":file_type_or_size");
            }
        } else {
            rejected_entries->push_back(child_path + ":file_type_or_size");
        }
    }
    closedir(directory);
}

void SortFiles(std::vector<IndexedModFile>* files) {
    std::sort(files->begin(), files->end(), [](const IndexedModFile& left, const IndexedModFile& right) {
        return left.relative_path < right.relative_path;
    });
}

}  // namespace

ModFileIndex ScanModRoot(std::string_view mod_root) {
    ModFileIndex index;
    if (mod_root.empty()) {
        return index;
    }

    const std::string root(mod_root);
    DIR* directory = opendir(root.c_str());
    if (directory == nullptr) {
        return index;
    }

    while (dirent* entry = readdir(directory)) {
        const std::string_view name(entry->d_name);
        if (name == "." || name == "..") {
            continue;
        }
        if (!IsSafePathComponent(name)) {
            index.rejected_entries.push_back(root + "/" + std::string(name) + ":mod_name");
            continue;
        }
        const std::string mod_root_path = root + "/" + std::string(name);
        struct stat status {};
        if (lstat(mod_root_path.c_str(), &status) != 0 || !S_ISDIR(status.st_mode) ||
            S_ISLNK(status.st_mode)) {
            index.rejected_entries.push_back(mod_root_path + ":mod_directory");
            continue;
        }

        IndexedMod mod{std::string(name), mod_root_path, {}, {}, {}};
        ScanDirectory(&mod, mod.root, "", 0, &index.rejected_entries);
        SortFiles(&mod.config_files);
        SortFiles(&mod.image_files);
        SortFiles(&mod.audio_files);
        index.mods.push_back(std::move(mod));
    }
    closedir(directory);

    std::sort(index.mods.begin(), index.mods.end(), [](const IndexedMod& left, const IndexedMod& right) {
        return left.name < right.name;
    });
    std::sort(index.rejected_entries.begin(), index.rejected_entries.end());
    return index;
}

std::optional<std::string> ReadIndexedFile(const IndexedModFile& file,
                                           std::size_t maximum_size) {
    if (file.size > maximum_size || file.absolute_path.empty()) {
        return std::nullopt;
    }
    const int descriptor = open(file.absolute_path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (descriptor < 0) {
        return std::nullopt;
    }

    struct stat status {};
    if (fstat(descriptor, &status) != 0 ||
        !IsRegularFile(status, maximum_size) ||
        static_cast<std::size_t>(status.st_size) != file.size || file.size > maximum_size) {
        close(descriptor);
        return std::nullopt;
    }

    std::string contents(file.size, '\0');
    std::size_t consumed = 0;
    while (consumed < contents.size()) {
        const ssize_t read_count = read(descriptor, contents.data() + consumed,
                                        contents.size() - consumed);
        if (read_count <= 0) {
            close(descriptor);
            return std::nullopt;
        }
        consumed += static_cast<std::size_t>(read_count);
    }
    close(descriptor);
    return contents;
}

}  // namespace modloader
