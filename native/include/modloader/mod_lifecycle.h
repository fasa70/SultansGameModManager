#pragma once

#include <chrono>
#include <functional>
#include <mutex>

namespace modloader {

class LifecycleGate {
  public:
    using ScheduleFunction = std::function<void(std::chrono::milliseconds, std::function<void()>)>;
    using ReadyFunction = std::function<void()>;

    LifecycleGate(ScheduleFunction schedule, ReadyFunction on_ready);

    void ObserveRitePostProcess();
    void ObserveEventPostProcess();

  private:
    void ScheduleIfReady();

    ScheduleFunction schedule_;
    ReadyFunction on_ready_;
    std::mutex mutex_;
    bool rite_finished_ = false;
    bool event_finished_ = false;
    bool fired_ = false;
    std::size_t generation_ = 0;
};

}  // namespace modloader
