#pragma once

#include "modloader/mod_file_index.h"

#include <cstddef>
#include <string>
#include <unordered_map>
#include <vector>

namespace modloader {

struct ResourceOverride {
    std::string mod_name;
    std::string relative_path;
    std::string absolute_path;
};

struct ResourceOverrideCollision {
    std::string key;
    ResourceOverride previous;
    ResourceOverride winner;
};

struct ResourceModStats {
    std::string mod_name;
    std::size_t image_files = 0;
    std::size_t accepted_images = 0;
    std::size_t rejected_images = 0;
};

struct ResourceOverrideIndex {
    std::unordered_map<std::string, ResourceOverride> images;
    std::unordered_map<std::string, ResourceOverride> audio;
    std::vector<ResourceOverrideCollision> image_collisions;
    std::vector<ResourceModStats> mod_stats;
    std::size_t rejected = 0;
};

ResourceOverrideIndex BuildResourceOverrideIndex(const ModFileIndex& index);

}  // namespace modloader
