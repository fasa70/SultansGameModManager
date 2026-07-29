#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <string_view>

namespace modloader {

enum class HookTarget : std::size_t {
    kRefreshMods,
    kLoadUserMods,
    kLoadGlobalMods,
    kModLoaderRun,
    kLoadConfig,
    kCount,
};

struct CodeFingerprint {
    std::uintptr_t rva;
    std::array<std::uint8_t, 16> bytes;
};

struct GameProfile {
    std::string_view name;
    std::array<CodeFingerprint, static_cast<std::size_t>(HookTarget::kCount)> fingerprints;
};

const GameProfile& SupportedGameProfile();
bool MatchesGameProfile(const GameProfile& profile,
                        std::uintptr_t image_base,
                        std::uintptr_t image_start,
                        std::uintptr_t image_end);
std::uintptr_t TargetAddress(const GameProfile& profile,
                             HookTarget target,
                             std::uintptr_t image_base);

}  // namespace modloader
