#include "modloader/mod_lifecycle.h"

namespace modloader {
namespace {

constexpr auto kDebounceDelay = std::chrono::milliseconds(1500);

}  // namespace

LifecycleGate::LifecycleGate(ScheduleFunction schedule, ReadyFunction on_ready)
    : schedule_(std::move(schedule)), on_ready_(std::move(on_ready)) {}

void LifecycleGate::ObserveRitePostProcess() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        rite_finished_ = true;
    }
    ScheduleIfReady();
}

void LifecycleGate::ObserveEventPostProcess() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        event_finished_ = true;
    }
    ScheduleIfReady();
}

void LifecycleGate::ScheduleIfReady() {
    std::size_t generation = 0;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!rite_finished_ || !event_finished_ || fired_) {
            return;
        }
        generation = ++generation_;
    }

    schedule_(kDebounceDelay, [this, generation]() {
        ReadyFunction on_ready;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (fired_ || generation != generation_) {
                return;
            }
            fired_ = true;
            on_ready = on_ready_;
        }
        on_ready();
    });
}

}  // namespace modloader
