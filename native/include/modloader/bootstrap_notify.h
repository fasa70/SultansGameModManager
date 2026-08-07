#pragma once

#include <cstddef>

namespace modloader {

constexpr std::size_t kOfficialModsAppliedCount = static_cast<std::size_t>(-1);

void NotifyModsApplied(std::size_t count) noexcept;

}  // namespace modloader
