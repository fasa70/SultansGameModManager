#pragma once

#include "modloader/il2cpp_api.h"

#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace modloader {

class Il2CppAttachedThread {
  public:
    explicit Il2CppAttachedThread(const Il2CppApi& api);
    ~Il2CppAttachedThread();

    Il2CppAttachedThread(const Il2CppAttachedThread&) = delete;
    Il2CppAttachedThread& operator=(const Il2CppAttachedThread&) = delete;

    bool attached() const;

  private:
    const Il2CppApi& api_;
    void* thread_ = nullptr;
    bool owns_attachment_ = false;
};

class GcHandle {
  public:
    GcHandle() = default;
    GcHandle(const Il2CppApi& api, void* object, bool pinned);
    ~GcHandle();

    GcHandle(const GcHandle&) = delete;
    GcHandle& operator=(const GcHandle&) = delete;
    GcHandle(GcHandle&& other) noexcept;
    GcHandle& operator=(GcHandle&& other) noexcept;

    bool valid() const;
    void Reset();

  private:
    const Il2CppApi* api_ = nullptr;
    std::uint32_t handle_ = 0;
};

class Il2CppRuntime {
  public:
    struct InstanceField {
        std::string_view name;
        void* handle = nullptr;
        std::int32_t offset = -1;
        std::size_t value_size = 0;
        bool is_reference = false;
    };

    explicit Il2CppRuntime(const Il2CppApi& api);

    std::optional<void*> FindImage(const std::vector<std::string_view>& assemblies) const;
    std::optional<void*> FindClass(void* image,
                                   const std::vector<std::string_view>& namespaces,
                                   std::string_view name) const;
    std::optional<void*> FindNestedClass(void* klass, std::string_view name) const;
    std::optional<void*> FindMethod(void* klass, std::string_view name,
                                    std::uint32_t parameter_count) const;
    std::optional<void*> FindMethodByFirstParameter(void* klass, std::string_view name,
                                                    std::uint32_t parameter_count,
                                                    std::string_view first_parameter_type) const;
    std::optional<void*> FindField(void* klass, std::string_view name) const;
    std::vector<InstanceField> InstanceFields(void* klass) const;
    std::optional<std::int32_t> FieldOffset(void* klass, std::string_view name) const;
    bool SetFieldValue(void* object, void* klass, std::string_view name, void* value) const;
    std::optional<void*> NewArray(void* element_class, std::size_t length) const;
    std::optional<void*> NewObject(void* klass) const;
    std::optional<void*> NewObjectForField(void* owner_class, std::string_view field_name) const;
    std::optional<void*> NewString(std::string_view text) const;
    std::optional<void*> ValueBox(void* klass, void* value) const;
    GcHandle Retain(void* object, bool pinned) const;
    std::optional<void*> StaticFieldValue(void* klass, std::string_view name) const;
    std::optional<std::int32_t> InstanceFieldInt32(
        void* object, std::string_view name) const;
    std::optional<void*> InstanceFieldValue(void* object, std::string_view name) const;
    std::optional<void*> ObjectClass(void* object) const;
    std::optional<void*> ObjectType(void* object) const;
    std::optional<void*> ManagedType(void* object) const;
    std::optional<void*> Unbox(void* object) const;
    std::optional<const void*> ClassType(void* klass) const;
    std::optional<void*> Invoke(void* method, void* instance, void** parameters) const;
    std::optional<void*> Invoke(void* method, void* instance, void** parameters,
                                void** exception) const;
    bool InvokeVoid(void* method, void* instance, void** parameters) const;
    std::optional<void*> MethodCode(void* method) const;
    bool IsExecutableCode(void* address) const;

  private:
    const Il2CppApi& api_;
};

}  // namespace modloader
