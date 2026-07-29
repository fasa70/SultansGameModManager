#pragma once

#include "modloader/il2cpp_runtime.h"

#include <optional>
#include <string_view>

namespace modloader {

class JsonBridge {
  public:
    explicit JsonBridge(const Il2CppRuntime& runtime);

    bool Probe();
    std::optional<void*> DeserializeInto(void* target, std::string_view json) const;
    std::optional<void*> DeserializeNode(void* common_image, std::string_view handler_name,
                                         std::string_view json) const;

  private:
    std::optional<void*> FindByteArrayDeserialize() const;
    std::optional<void*> ManagedTypeOf(void* target) const;

    const Il2CppRuntime& runtime_;
    void* serializer_class_ = nullptr;
    void* byte_class_ = nullptr;
};

}  // namespace modloader
