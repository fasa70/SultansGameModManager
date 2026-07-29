#include "modloader/runtime_state.h"

namespace modloader {
namespace {

constexpr std::uint64_t Pack(RuntimeState state, FailureCode failure) {
    return static_cast<std::uint64_t>(static_cast<std::uint32_t>(state)) |
           (static_cast<std::uint64_t>(static_cast<std::uint32_t>(failure)) << 32U);
}

constexpr RuntimeState UnpackState(std::uint64_t snapshot) {
    return static_cast<RuntimeState>(static_cast<std::uint32_t>(snapshot));
}

constexpr FailureCode UnpackFailure(std::uint64_t snapshot) {
    return static_cast<FailureCode>(static_cast<std::uint32_t>(snapshot >> 32U));
}

}

bool RuntimeController::TryBeginWaiting() {
    std::uint64_t expected = Pack(RuntimeState::kNotStarted, FailureCode::kNone);
    return snapshot_.compare_exchange_strong(
        expected,
        Pack(RuntimeState::kWaitingForIl2Cpp, FailureCode::kNone),
        std::memory_order_acq_rel);
}

bool RuntimeController::MarkInitializing() {
    std::uint64_t expected = Pack(RuntimeState::kWaitingForIl2Cpp, FailureCode::kNone);
    if (snapshot_.compare_exchange_strong(
            expected,
            Pack(RuntimeState::kInitializing, FailureCode::kNone),
            std::memory_order_acq_rel)) {
        return true;
    }
    return UnpackState(expected) == RuntimeState::kInitializing;
}

bool RuntimeController::MarkReady() {
    std::uint64_t expected = Pack(RuntimeState::kInitializing, FailureCode::kNone);
    return snapshot_.compare_exchange_strong(
        expected,
        Pack(RuntimeState::kReady, FailureCode::kNone),
        std::memory_order_acq_rel);
}

bool RuntimeController::MarkUnsupported() {
    std::uint64_t expected = Pack(RuntimeState::kInitializing, FailureCode::kNone);
    return snapshot_.compare_exchange_strong(
        expected,
        Pack(RuntimeState::kUnsupported, FailureCode::kUnsupportedGameVersion),
        std::memory_order_acq_rel);
}

bool RuntimeController::Fail(FailureCode failure) {
    std::uint64_t current = snapshot_.load(std::memory_order_acquire);
    while (UnpackState(current) != RuntimeState::kReady &&
           UnpackState(current) != RuntimeState::kFailed &&
           UnpackState(current) != RuntimeState::kUnsupported) {
        if (snapshot_.compare_exchange_weak(
                current,
                Pack(RuntimeState::kFailed, failure),
                std::memory_order_acq_rel)) {
            return true;
        }
    }
    return false;
}

RuntimeState RuntimeController::state() const {
    return UnpackState(snapshot_.load(std::memory_order_acquire));
}

FailureCode RuntimeController::failure() const {
    return UnpackFailure(snapshot_.load(std::memory_order_acquire));
}

void RunResolverLoop(RuntimeController& runtime,
                     Il2CppResolver& resolver,
                     std::size_t max_attempts,
                     std::chrono::milliseconds interval,
                     const WaitFunction& wait) {
    ResolveStatus last_status = ResolveStatus::kNotLoaded;
    for (std::size_t attempt = 0; attempt < max_attempts; ++attempt) {
        last_status = resolver.TryResolve();
        if (last_status == ResolveStatus::kReady) {
            if (!runtime.MarkInitializing()) {
                runtime.Fail(FailureCode::kUnexpectedNativeException);
            }
            return;
        }
        if (last_status == ResolveStatus::kRequiredSymbolMissing) {
            runtime.Fail(FailureCode::kIl2CppRequiredSymbolMissing);
            return;
        }
        if (attempt + 1 < max_attempts) {
            wait(interval);
        }
    }

    switch (last_status) {
        case ResolveStatus::kNoLoadHandle:
            runtime.Fail(FailureCode::kIl2CppNoLoadHandle);
            break;
        case ResolveStatus::kDomainUnavailable:
            runtime.Fail(FailureCode::kIl2CppDomainUnavailable);
            break;
        default:
            runtime.Fail(FailureCode::kIl2CppTimeout);
            break;
    }
}

}  // namespace modloader
