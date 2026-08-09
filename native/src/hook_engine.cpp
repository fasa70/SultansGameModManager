#include "modloader/hook_engine.h"

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wvariadic-macros"
#include <dobby.h>
#pragma clang diagnostic pop

namespace modloader {

bool HookEngine::Replace(void* target, void* replacement, void** original) {
    if (target == nullptr || replacement == nullptr || original == nullptr || count_ == kMaxHooks ||
        DobbyHook(target, replacement, original) != 0) {
        return false;
    }
    hooks_[count_++] = {target};
    return true;
}

bool HookEngine::Instrument(void* target, InstrumentCallback callback) {
    if (target == nullptr || callback == nullptr || count_ == kMaxHooks ||
        DobbyInstrument(target, reinterpret_cast<dobby_instrument_callback_t>(callback)) != 0) {
        return false;
    }
    hooks_[count_++] = {target};
    return true;
}

void HookEngine::Rollback() {
    while (count_ != 0) {
        --count_;
        DobbyDestroy(hooks_[count_].target);
        hooks_[count_].target = nullptr;
    }
}

}  // namespace modloader
