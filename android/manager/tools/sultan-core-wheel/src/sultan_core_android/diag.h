#pragma once
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace sultan {

enum class DiagLevel : uint8_t { Info, Warn, Error };

struct DiagMessage {
    DiagLevel level;
    std::string category;
    std::string message;
};

class DiagManager {
public:
    using Callback = std::function<void(DiagLevel, const std::string&,
                                        const std::string&)>;

    void emit(DiagLevel level, const std::string& category,
              const std::string& msg, bool notify = false);

    void info(const std::string& category, const std::string& msg,
              bool notify = false);
    void warn(const std::string& category, const std::string& msg,
              bool notify = false);
    void error(const std::string& category, const std::string& msg,
               bool notify = false);

    std::vector<DiagMessage> snapshot();
    std::vector<DiagMessage> snapshot(
        const std::vector<std::string>& categories);

    void set_callback(Callback cb);
    void clear_callback();

private:
    std::mutex mutex_;
    std::unordered_map<std::string, std::vector<DiagMessage>> buffer_;
    Callback callback_;
};

DiagManager& diag_manager();

}  // namespace sultan
