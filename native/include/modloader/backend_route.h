#pragma once

#include <atomic>
#include <cstdint>

namespace modloader {

#ifndef MODLOADER_BACKEND_MODE
#define MODLOADER_BACKEND_MODE 0
#endif

#if MODLOADER_BACKEND_MODE < 0 || MODLOADER_BACKEND_MODE > 1
#error "MODLOADER_BACKEND_MODE must be 0 or 1"
#endif

enum class BackendRoute : std::uint8_t {
    kUnselected = 0,
    kStagedNative = 1,
    kOfficialCanary = 2,
};

enum class BackendRoutePhase : std::uint8_t {
    kUnselected = 0,
    kPreflight = 1,
    kStarted = 2,
    kReady = 3,
    kFailed = 4,
};

constexpr BackendRoute CompiledBackendRoute() noexcept {
    return MODLOADER_BACKEND_MODE == 1
        ? BackendRoute::kOfficialCanary
        : BackendRoute::kStagedNative;
}

const char* BackendRouteName(BackendRoute route) noexcept;
const char* BackendRoutePhaseName(BackendRoutePhase phase) noexcept;

class BackendRouteController final {
  public:
    bool Claim(BackendRoute route) noexcept;
    bool MarkStarted(BackendRoute route) noexcept;
    bool MarkReady(BackendRoute route) noexcept;
    bool MarkFailed(BackendRoute route) noexcept;

    BackendRoute route() const noexcept;
    BackendRoutePhase phase() const noexcept;
    bool Is(BackendRoute route, BackendRoutePhase phase) const noexcept;

  private:
    std::atomic<std::uint16_t> snapshot_{0};
};

}  // namespace modloader
