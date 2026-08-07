#include "modloader/game_profile.h"

#include <cstring>

namespace modloader {
namespace {

constexpr GameProfile kOfficialAndroidProfile{
    "official-android-2026-07-27",
    {{{0x1e59984, {0xff, 0x03, 0x02, 0xd1, 0xfd, 0x7b, 0x02, 0xa9,
                    0xfc, 0x6f, 0x03, 0xa9, 0xfa, 0x67, 0x04, 0xa9}},
      {0x1e59568, {0xfe, 0x0f, 0x1e, 0xf8, 0xf4, 0x4f, 0x01, 0xa9,
                    0xf3, 0x36, 0x01, 0xf0, 0x68, 0xf2, 0x6d, 0x39}},
      {0x1e59fa4, {0xfe, 0x57, 0xbe, 0xa9, 0xf4, 0x4f, 0x01, 0xa9,
                    0xf4, 0x36, 0x01, 0xf0, 0x35, 0x24, 0x01, 0xb0}},
      {0x1e59698, {0xfe, 0x0f, 0x1e, 0xf8, 0xf4, 0x4f, 0x01, 0xa9,
                    0xf4, 0x36, 0x01, 0xf0, 0xf3, 0x23, 0x01, 0x90}},
      {0x1e4f374, {0xfe, 0x0f, 0x1d, 0xf8, 0xf6, 0x57, 0x01, 0xa9,
                    0xf4, 0x4f, 0x02, 0xa9, 0x55, 0x37, 0x01, 0xb0}}}},
    {0x1e88f30, {0xe2, 0x03, 0x1f, 0xaa, 0x4e, 0x1d, 0x7d, 0x14,
                  0xe1, 0x03, 0x1f, 0xaa, 0x99, 0x1e, 0x7d, 0x14}},
};

}  // namespace

const GameProfile& SupportedGameProfile() {
    return kOfficialAndroidProfile;
}

bool MatchesGameProfile(const GameProfile& profile,
                        std::uintptr_t image_base,
                        std::uintptr_t image_start,
                        std::uintptr_t image_end) {
    if (image_base < image_start || image_base >= image_end) {
        return false;
    }

    for (const auto& fingerprint : profile.fingerprints) {
        const std::uintptr_t address = image_base + fingerprint.rva;
        if (address < image_base || address > image_end ||
            fingerprint.bytes.size() > image_end - address ||
            std::memcmp(reinterpret_cast<const void*>(address), fingerprint.bytes.data(),
                        fingerprint.bytes.size()) != 0) {
            return false;
        }
    }
    return true;
}

std::uintptr_t TargetAddress(const GameProfile& profile,
                             HookTarget target,
                             std::uintptr_t image_base) {
    return image_base + profile.fingerprints.at(static_cast<std::size_t>(target)).rva;
}

}  // namespace modloader
