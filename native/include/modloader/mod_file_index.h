#pragma once

#include <cstddef>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace modloader {

struct IndexedModFile {
    std::string relative_path;
    std::string absolute_path;
    std::size_t size = 0;
};

struct IndexedMod {
    std::string name;
    std::string root;
    std::vector<IndexedModFile> config_files;
    std::vector<IndexedModFile> image_files;
    std::vector<IndexedModFile> audio_files;
};

struct ModFileIndex {
    std::vector<IndexedMod> mods;
    std::vector<std::string> rejected_entries;
};

ModFileIndex ScanModRoot(std::string_view mod_root);
std::optional<std::string> ReadIndexedFile(const IndexedModFile& file,
                                           std::size_t maximum_size);

}  // namespace modloader
