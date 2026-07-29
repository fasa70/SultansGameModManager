#pragma once

#include "modloader/il2cpp_runtime.h"

#include <cstdint>
#include <optional>
#include <string_view>

namespace modloader {

class ManagedIntDictionary {
  public:
    explicit ManagedIntDictionary(const Il2CppRuntime& runtime);

    bool Probe(void* dictionary);
    std::optional<void*> Get(std::int32_t key) const;
    bool TryContains(std::int32_t key, bool* contains) const;
    bool Contains(std::int32_t key) const;
    bool Set(std::int32_t key, void* value) const;
    bool Remove(std::int32_t key) const;

  private:
    const Il2CppRuntime& runtime_;
    void* dictionary_ = nullptr;
    void* contains_ = nullptr;
    void* get_item_ = nullptr;
    void* set_item_ = nullptr;
    void* remove_ = nullptr;
};

class ManagedStringDictionary {
  public:
    explicit ManagedStringDictionary(const Il2CppRuntime& runtime);

    bool Probe(void* dictionary);
    std::optional<void*> Get(std::string_view key) const;
    bool TryContains(std::string_view key, bool* contains) const;
    bool Set(std::string_view key, void* value) const;
    bool Remove(std::string_view key) const;

  private:
    std::optional<void*> NewKey(std::string_view key, GcHandle* handle) const;

    const Il2CppRuntime& runtime_;
    void* dictionary_ = nullptr;
    void* contains_ = nullptr;
    void* get_item_ = nullptr;
    void* set_item_ = nullptr;
    void* remove_ = nullptr;
};

}  // namespace modloader
