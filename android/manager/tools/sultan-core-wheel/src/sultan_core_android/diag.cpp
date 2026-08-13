#include "diag.h"
#include <algorithm>
#include <iterator>

namespace sultan {

void DiagManager::emit(DiagLevel level, const std::string& category,
                       const std::string& msg, bool notify) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (notify && callback_) {
        callback_(level, category, msg);
        return;
    }
    buffer_[category].push_back({level, category, msg});
}

void DiagManager::info(const std::string& category, const std::string& msg,
                       bool notify) {
    emit(DiagLevel::Info, category, msg, notify);
}

void DiagManager::warn(const std::string& category, const std::string& msg,
                       bool notify) {
    emit(DiagLevel::Warn, category, msg, notify);
}

void DiagManager::error(const std::string& category, const std::string& msg,
                        bool notify) {
    emit(DiagLevel::Error, category, msg, notify);
}

std::vector<DiagMessage> DiagManager::snapshot() {
    std::lock_guard<std::mutex> lock(mutex_);
    std::vector<DiagMessage> result;
    for (auto& [cat, msgs] : buffer_) {
        result.insert(result.end(),
                      std::make_move_iterator(msgs.begin()),
                      std::make_move_iterator(msgs.end()));
    }
    buffer_.clear();
    return result;
}

std::vector<DiagMessage> DiagManager::snapshot(
    const std::vector<std::string>& categories) {
    std::lock_guard<std::mutex> lock(mutex_);
    std::vector<DiagMessage> result;
    for (const auto& cat : categories) {
        auto it = buffer_.find(cat);
        if (it != buffer_.end()) {
            result.insert(result.end(),
                          std::make_move_iterator(it->second.begin()),
                          std::make_move_iterator(it->second.end()));
            buffer_.erase(it);
        }
    }
    return result;
}

void DiagManager::set_callback(Callback cb) {
    std::lock_guard<std::mutex> lock(mutex_);
    callback_ = std::move(cb);
}

void DiagManager::clear_callback() {
    std::lock_guard<std::mutex> lock(mutex_);
    callback_ = nullptr;
}

DiagManager& diag_manager() {
    static DiagManager instance;
    return instance;
}

}  // namespace sultan
