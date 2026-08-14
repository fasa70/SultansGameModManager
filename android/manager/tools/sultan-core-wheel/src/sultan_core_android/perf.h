#pragma once

#ifdef SULTAN_PERF

#include <algorithm>
#include <atomic>
#include <chrono>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace sultan::perf {

struct Stats {
    std::atomic<int64_t> call_count{0};
    std::atomic<int64_t> total_ns{0};
    std::atomic<int64_t> max_ns{0};
    std::atomic<int64_t> min_ns{INT64_MAX};

    void record(int64_t ns) {
        call_count.fetch_add(1, std::memory_order_relaxed);
        total_ns.fetch_add(ns, std::memory_order_relaxed);
        int64_t prev = max_ns.load(std::memory_order_relaxed);
        while (ns > prev && !max_ns.compare_exchange_weak(
            prev, ns, std::memory_order_relaxed)) {}
        prev = min_ns.load(std::memory_order_relaxed);
        while (ns < prev && !min_ns.compare_exchange_weak(
            prev, ns, std::memory_order_relaxed)) {}
    }
};

struct SnapshotEntry {
    std::string name;
    int64_t call_count;
    double total_us;
    double avg_us;
    double max_us;
    double min_us;
};

class Registry {
public:
    static Registry& instance() {
        static Registry r;
        return r;
    }

    Stats& get(const char* name) {
        std::lock_guard<std::mutex> lock(mutex_);
        return entries_[name];
    }

    std::vector<SnapshotEntry> snapshot() const {
        std::lock_guard<std::mutex> lock(mutex_);
        std::vector<SnapshotEntry> result;
        result.reserve(entries_.size());
        for (auto& [name, s] : entries_) {
            int64_t count = s.call_count.load(std::memory_order_relaxed);
            if (count == 0) continue;
            double total_us = s.total_ns.load(std::memory_order_relaxed) / 1000.0;
            double avg_us = total_us / count;
            double max_us = s.max_ns.load(std::memory_order_relaxed) / 1000.0;
            double min_us = s.min_ns.load(std::memory_order_relaxed) / 1000.0;
            result.push_back({name, count, total_us, avg_us, max_us, min_us});
        }
        std::sort(result.begin(), result.end(),
            [](const SnapshotEntry& a, const SnapshotEntry& b) {
                return a.total_us > b.total_us;
            });
        return result;
    }

    void reset() {
        std::lock_guard<std::mutex> lock(mutex_);
        entries_.clear();
    }

    void set_enabled(bool v) { enabled_.store(v, std::memory_order_relaxed); }
    bool enabled() const { return enabled_.load(std::memory_order_relaxed); }

private:
    Registry() = default;
    mutable std::mutex mutex_;
    std::unordered_map<std::string, Stats> entries_;
    std::atomic<bool> enabled_{true};
};

class ScopeTimer {
public:
    explicit ScopeTimer(const char* name)
        : name_(name), reg_(Registry::instance())
    {
        if (reg_.enabled())
            start_ = std::chrono::steady_clock::now();
    }

    ~ScopeTimer() {
        if (reg_.enabled() && start_ != time_point{}) {
            auto ns = std::chrono::duration_cast<std::chrono::nanoseconds>(
                std::chrono::steady_clock::now() - start_).count();
            reg_.get(name_).record(ns);
        }
    }

    ScopeTimer(const ScopeTimer&) = delete;
    ScopeTimer& operator=(const ScopeTimer&) = delete;

private:
    using time_point = std::chrono::steady_clock::time_point;
    const char* name_;
    Registry& reg_;
    time_point start_{};
};

}  // namespace sultan::perf

#define SULTAN_PERF_SCOPE(name) \
    ::sultan::perf::ScopeTimer sultan_perf_timer_##__LINE__(name)

#else  // !SULTAN_PERF

#define SULTAN_PERF_SCOPE(name) ((void)0)

#endif  // SULTAN_PERF
