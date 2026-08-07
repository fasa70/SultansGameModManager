#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>

namespace modloader {

enum class OfficialPromiseCompletion : std::uint8_t {
    kResolved = 1,
    kRejected = 2,
};

enum class OfficialPromiseState : std::uint8_t {
    kPending = 0,
    kRejected = 1,
    kResolved = 2,
};

enum class OfficialCanaryDecision : std::uint8_t {
    kUnchanged = 0,
    kPending = 1,
    kReady = 2,
    kFailed = 3,
};

class OfficialCanaryCompletion final {
  public:
    bool BeginRefreshCall() noexcept;
    OfficialCanaryDecision TrackPromise(const void* promise) noexcept;
    OfficialCanaryDecision ObservePromise(
        const void* promise,
        OfficialPromiseCompletion completion) noexcept;
    OfficialCanaryDecision ObservePromiseState(
        const void* promise,
        OfficialPromiseState state) noexcept;
    OfficialCanaryDecision ObserveLoadConfig(bool iterator_returned) noexcept;
    OfficialCanaryDecision Fail() noexcept;
    OfficialCanaryDecision decision() const noexcept;

  private:
    OfficialCanaryDecision Transition(std::uint8_t flags) noexcept;

    mutable std::mutex mutex_;
    const void* promise_ = nullptr;
    bool call_active_ = false;
    std::atomic<bool> call_started_{false};
    std::atomic<std::uint8_t> snapshot_{0};
};

}  // namespace modloader
