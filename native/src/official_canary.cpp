#include "modloader/official_canary.h"

namespace modloader {
namespace {

constexpr std::uint8_t kPromiseResolved = 1U << 0U;
constexpr std::uint8_t kLoadConfigReturned = 1U << 1U;
constexpr std::uint8_t kTerminalReady = 1U << 2U;
constexpr std::uint8_t kTerminalFailed = 1U << 3U;

OfficialCanaryDecision Decision(std::uint8_t snapshot) {
    if ((snapshot & kTerminalFailed) != 0) {
        return OfficialCanaryDecision::kFailed;
    }
    if ((snapshot & kTerminalReady) != 0) {
        return OfficialCanaryDecision::kReady;
    }
    return OfficialCanaryDecision::kPending;
}

}  // namespace

bool OfficialCanaryCompletion::BeginRefreshCall() noexcept {
    bool expected = false;
    if (!call_started_.compare_exchange_strong(
            expected, true, std::memory_order_acq_rel)) {
        return false;
    }
    std::lock_guard<std::mutex> lock(mutex_);
    promise_ = nullptr;
    call_active_ = true;
    snapshot_.store(0, std::memory_order_release);
    return true;
}

OfficialCanaryDecision OfficialCanaryCompletion::TrackPromise(
    const void* promise) noexcept {
    if (promise == nullptr) {
        return Fail();
    }
    std::lock_guard<std::mutex> lock(mutex_);
    promise_ = promise;
    call_active_ = false;
    return Decision(snapshot_.load(std::memory_order_acquire));
}

OfficialCanaryDecision OfficialCanaryCompletion::ObservePromise(
    const void* promise,
    OfficialPromiseCompletion completion) noexcept {
    if (promise == nullptr) {
        return OfficialCanaryDecision::kUnchanged;
    }
    std::lock_guard<std::mutex> lock(mutex_);
    if (call_active_ || promise_ != promise) {
        return OfficialCanaryDecision::kUnchanged;
    }
    return completion == OfficialPromiseCompletion::kRejected
        ? Transition(kTerminalFailed)
        : Transition(kPromiseResolved);
}

OfficialCanaryDecision OfficialCanaryCompletion::ObservePromiseState(
    const void* promise,
    OfficialPromiseState state) noexcept {
    if (promise == nullptr) {
        return OfficialCanaryDecision::kUnchanged;
    }
    std::lock_guard<std::mutex> lock(mutex_);
    if (call_active_ || promise_ != promise) {
        return OfficialCanaryDecision::kUnchanged;
    }
    switch (state) {
        case OfficialPromiseState::kResolved:
            return Transition(kPromiseResolved);
        case OfficialPromiseState::kRejected:
            return Transition(kTerminalFailed);
        case OfficialPromiseState::kPending:
            return Decision(snapshot_.load(std::memory_order_acquire));
    }
    return OfficialCanaryDecision::kUnchanged;
}

OfficialCanaryDecision OfficialCanaryCompletion::ObserveLoadConfig(
    bool iterator_returned) noexcept {
    return iterator_returned ? Transition(kLoadConfigReturned) : Fail();
}

OfficialCanaryDecision OfficialCanaryCompletion::Fail() noexcept {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        call_active_ = false;
    }
    return Transition(kTerminalFailed);
}

OfficialCanaryDecision OfficialCanaryCompletion::decision() const noexcept {
    return Decision(snapshot_.load(std::memory_order_acquire));
}

OfficialCanaryDecision OfficialCanaryCompletion::Transition(
    std::uint8_t flags) noexcept {
    std::uint8_t current = snapshot_.load(std::memory_order_acquire);
    while (true) {
        if ((current & (kTerminalReady | kTerminalFailed)) != 0) {
            return Decision(current);
        }
        std::uint8_t desired = static_cast<std::uint8_t>(current | flags);
        if ((desired & kTerminalFailed) == 0 &&
            (desired & (kPromiseResolved | kLoadConfigReturned)) ==
                (kPromiseResolved | kLoadConfigReturned)) {
            desired = static_cast<std::uint8_t>(desired | kTerminalReady);
        }
        if (snapshot_.compare_exchange_weak(
                current, desired, std::memory_order_acq_rel)) {
            return Decision(desired);
        }
    }
}

}  // namespace modloader
