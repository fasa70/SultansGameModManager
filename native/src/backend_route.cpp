#include "modloader/backend_route.h"

namespace modloader {
namespace {

constexpr std::uint16_t Pack(BackendRoute route, BackendRoutePhase phase) noexcept {
    return static_cast<std::uint16_t>(route) |
        static_cast<std::uint16_t>(static_cast<std::uint16_t>(phase) << 8U);
}

constexpr BackendRoute UnpackRoute(std::uint16_t snapshot) noexcept {
    return static_cast<BackendRoute>(snapshot & 0xffU);
}

constexpr BackendRoutePhase UnpackPhase(std::uint16_t snapshot) noexcept {
    return static_cast<BackendRoutePhase>((snapshot >> 8U) & 0xffU);
}

bool Transition(std::atomic<std::uint16_t>* snapshot,
                BackendRoute route,
                BackendRoutePhase from,
                BackendRoutePhase to) noexcept {
    std::uint16_t expected = Pack(route, from);
    return snapshot->compare_exchange_strong(
        expected, Pack(route, to), std::memory_order_acq_rel);
}

}  // namespace

const char* BackendRouteName(BackendRoute route) noexcept {
    switch (route) {
        case BackendRoute::kUnselected:
            return "unselected";
        case BackendRoute::kStagedNative:
            return "staged_native";
        case BackendRoute::kOfficialCanary:
            return "official_canary";
    }
    return "unknown";
}

const char* BackendRoutePhaseName(BackendRoutePhase phase) noexcept {
    switch (phase) {
        case BackendRoutePhase::kUnselected:
            return "unselected";
        case BackendRoutePhase::kPreflight:
            return "preflight";
        case BackendRoutePhase::kStarted:
            return "started";
        case BackendRoutePhase::kReady:
            return "ready";
        case BackendRoutePhase::kFailed:
            return "failed";
    }
    return "unknown";
}

bool BackendRouteController::Claim(BackendRoute route) noexcept {
    if (route == BackendRoute::kUnselected) {
        return false;
    }
    std::uint16_t expected = Pack(
        BackendRoute::kUnselected, BackendRoutePhase::kUnselected);
    return snapshot_.compare_exchange_strong(
        expected, Pack(route, BackendRoutePhase::kPreflight),
        std::memory_order_acq_rel);
}

bool BackendRouteController::MarkStarted(BackendRoute route) noexcept {
    return Transition(
        &snapshot_, route, BackendRoutePhase::kPreflight,
        BackendRoutePhase::kStarted);
}

bool BackendRouteController::MarkReady(BackendRoute route) noexcept {
    return Transition(
        &snapshot_, route, BackendRoutePhase::kStarted,
        BackendRoutePhase::kReady);
}

bool BackendRouteController::MarkFailed(BackendRoute route) noexcept {
    std::uint16_t current = snapshot_.load(std::memory_order_acquire);
    while (UnpackRoute(current) == route &&
           UnpackPhase(current) != BackendRoutePhase::kReady &&
           UnpackPhase(current) != BackendRoutePhase::kFailed) {
        if (snapshot_.compare_exchange_weak(
                current, Pack(route, BackendRoutePhase::kFailed),
                std::memory_order_acq_rel)) {
            return true;
        }
    }
    return false;
}

BackendRoute BackendRouteController::route() const noexcept {
    return UnpackRoute(snapshot_.load(std::memory_order_acquire));
}

BackendRoutePhase BackendRouteController::phase() const noexcept {
    return UnpackPhase(snapshot_.load(std::memory_order_acquire));
}

bool BackendRouteController::Is(BackendRoute route,
                                BackendRoutePhase phase) const noexcept {
    return snapshot_.load(std::memory_order_acquire) == Pack(route, phase);
}

}  // namespace modloader
