#include "modloader/android_log.h"

#include <android/log.h>

namespace modloader {
namespace {

constexpr const char* kLogTag = "SultanModLoader";

const char* StateName(RuntimeState state) {
    switch (state) {
        case RuntimeState::kNotStarted:
            return "not_started";
        case RuntimeState::kWaitingForIl2Cpp:
            return "waiting_for_il2cpp";
        case RuntimeState::kInitializing:
            return "initializing";
        case RuntimeState::kReady:
            return "ready";
        case RuntimeState::kFailed:
            return "failed";
        case RuntimeState::kUnsupported:
            return "unsupported";
    }
    return "unknown";
}

const char* FailureName(FailureCode failure) {
    switch (failure) {
        case FailureCode::kNone:
            return "none";
        case FailureCode::kInvalidContext:
            return "invalid_context";
        case FailureCode::kExternalFilesUnavailable:
            return "external_files_unavailable";
        case FailureCode::kIl2CppTimeout:
            return "il2cpp_timeout";
        case FailureCode::kIl2CppNoLoadHandle:
            return "il2cpp_no_load_handle";
        case FailureCode::kIl2CppRequiredSymbolMissing:
            return "il2cpp_required_symbol_missing";
        case FailureCode::kIl2CppDomainUnavailable:
            return "il2cpp_domain_unavailable";
        case FailureCode::kUnexpectedNativeException:
            return "unexpected_native_exception";
        case FailureCode::kUnsupportedGameVersion:
            return "unsupported_game_version";
        case FailureCode::kHookInstallFailed:
            return "hook_install_failed";
        case FailureCode::kIl2CppReflectionUnavailable:
            return "il2cpp_reflection_unavailable";
        case FailureCode::kOfficialPreflightFailed:
            return "official_preflight_failed";
        case FailureCode::kOfficialInvocationFailed:
            return "official_invocation_failed";
    }
    return "unknown";
}

}

void LogState(RuntimeState state) {
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "state=%s", StateName(state));
}

void LogFailure(FailureCode failure) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "failure=%s", FailureName(failure));
}

void LogMessage(const char* message) {
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "%s", message);
}

}
