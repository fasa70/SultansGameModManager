#include "modloader/resource_overrides.h"

#include <algorithm>
#include <string_view>

namespace modloader {
namespace {

bool StartsWith(std::string_view value, std::string_view prefix) {
    return value.size() >= prefix.size() && value.compare(0, prefix.size(), prefix) == 0;
}

std::string WithoutExtension(std::string_view value) {
    const std::size_t separator = value.find_last_of('/');
    const std::size_t extension = value.find_last_of('.');
    if (extension == std::string_view::npos ||
        (separator != std::string_view::npos && extension < separator)) {
        return {};
    }
    return std::string(value.substr(0, extension));
}

std::string NormalizeKey(std::string_view relative_path) {
    std::string key(relative_path);
    std::replace(key.begin(), key.end(), '\\', '/');
    return key;
}

ResourceOverride MakeOverride(const IndexedMod& mod, const IndexedModFile& file) {
    return {mod.name, file.relative_path, file.absolute_path};
}

void AddImageOverride(ResourceOverrideIndex* overrides, std::string key,
                      ResourceOverride override) {
    const auto existing = overrides->images.find(key);
    if (existing != overrides->images.end()) {
        overrides->image_collisions.push_back({key, existing->second, override});
    }
    overrides->images[std::move(key)] = std::move(override);
}

}  // namespace

ResourceOverrideIndex BuildResourceOverrideIndex(const ModFileIndex& index) {
    ResourceOverrideIndex overrides;
    for (const IndexedMod& mod : index.mods) {
        ResourceModStats stats;
        stats.mod_name = mod.name;
        stats.image_files = mod.image_files.size();
        for (const IndexedModFile& file : mod.image_files) {
            constexpr std::string_view kImagePrefix = "image/";
            if (!StartsWith(file.relative_path, kImagePrefix)) {
                ++overrides.rejected;
                ++stats.rejected_images;
                continue;
            }
            const std::string key = WithoutExtension(
                NormalizeKey(std::string_view(file.relative_path).substr(kImagePrefix.size())));
            if (key.empty()) {
                ++overrides.rejected;
                ++stats.rejected_images;
                continue;
            }
            AddImageOverride(&overrides, key, MakeOverride(mod, file));
            ++stats.accepted_images;
        }
        overrides.mod_stats.push_back(std::move(stats));
        for (const IndexedModFile& file : mod.audio_files) {
            constexpr std::string_view kAudioPrefix = "bgm/";
            if (!StartsWith(file.relative_path, kAudioPrefix)) {
                ++overrides.rejected;
                continue;
            }
            const std::string normalized = NormalizeKey(
                std::string_view(file.relative_path).substr(kAudioPrefix.size()));
            const std::size_t separator = normalized.find_last_of('/');
            const std::string key = WithoutExtension(
                separator == std::string::npos ? std::string_view(normalized) :
                    std::string_view(normalized).substr(separator + 1));
            if (key.empty()) {
                ++overrides.rejected;
                continue;
            }
            overrides.audio[key] = MakeOverride(mod, file);
        }
    }
    return overrides;
}

}  // namespace modloader
