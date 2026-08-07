#pragma once

#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <functional>

namespace modloader {

enum class RuntimeState : std::int32_t {
    kNotStarted = 0,
    kWaitingForIl2Cpp = 1,
    kInitializing = 2,
    kReady = 3,
    kFailed = 4,
    kUnsupported = 5,
};

enum class FailureCode : std::int32_t {
    kNone = 0,
    kInvalidContext = 1,
    kExternalFilesUnavailable = 2,
    kIl2CppTimeout = 3,
    kIl2CppNoLoadHandle = 4,
    kIl2CppRequiredSymbolMissing = 5,
    kIl2CppDomainUnavailable = 6,
    kUnexpectedNativeException = 7,
    kUnsupportedGameVersion = 8,
    kHookInstallFailed = 9,
    kIl2CppReflectionUnavailable = 10,
    kOfficialPreflightFailed = 11,
    kOfficialInvocationFailed = 12,
};

enum class ResolveStatus {
    kNotLoaded,
    kNoLoadHandle,
    kRequiredSymbolMissing,
    kDomainUnavailable,
    kReady,
};

class Il2CppResolver {
  public:
    virtual ~Il2CppResolver() = default;
    virtual ResolveStatus TryResolve() = 0;
};

class RuntimeController {
  public:
    bool TryBeginWaiting();
    bool MarkInitializing();
    bool MarkReady();
    bool MarkUnsupported();
    bool Fail(FailureCode failure);

    RuntimeState state() const;
    FailureCode failure() const;

  private:
    std::atomic<std::uint64_t> snapshot_{0};
};

using WaitFunction = std::function<void(std::chrono::milliseconds)>;

void RunResolverLoop(RuntimeController& runtime,
                     Il2CppResolver& resolver,
                     std::size_t max_attempts,
                     std::chrono::milliseconds interval,
                     const WaitFunction& wait);

}  // namespace modloader
