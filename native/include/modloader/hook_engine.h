#pragma once

#include <cstddef>

namespace modloader {

class HookEngine {
  public:
    using InstrumentCallback = void (*)(void*, void*);

    bool Replace(void* target, void* replacement, void** original);
    bool Instrument(void* target, InstrumentCallback callback);
    void Rollback();

  private:
    struct InstalledHook {
        void* target;
    };

    static constexpr std::size_t kMaxHooks = 32;
    InstalledHook hooks_[kMaxHooks]{};
    std::size_t count_ = 0;
};

}  // namespace modloader
