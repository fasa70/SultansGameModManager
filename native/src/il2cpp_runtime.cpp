#include "modloader/il2cpp_runtime.h"

#include <link.h>

#include <cstring>
#include <utility>

namespace modloader {
namespace {

constexpr std::size_t kMethodCodePointerOffset = 0;

bool IsMatchingName(const char* actual, std::string_view expected) {
    return actual != nullptr && std::string_view(actual) == expected;
}

struct ExecutableSearch {
    std::uintptr_t address = 0;
    bool found = false;
};

int FindExecutableSegment(struct dl_phdr_info* info, size_t, void* data) {
    auto* search = static_cast<ExecutableSearch*>(data);
    for (ElfW(Half) index = 0; index < info->dlpi_phnum; ++index) {
        const ElfW(Phdr)& header = info->dlpi_phdr[index];
        if (header.p_type != PT_LOAD || (header.p_flags & PF_X) == 0) {
            continue;
        }
        const std::uintptr_t begin = static_cast<std::uintptr_t>(info->dlpi_addr) + header.p_vaddr;
        const std::uintptr_t end = begin + header.p_memsz;
        if (search->address >= begin && search->address < end) {
            search->found = true;
            return 1;
        }
    }
    return 0;
}

}  // namespace

Il2CppAttachedThread::Il2CppAttachedThread(const Il2CppApi& api) : api_(api) {
    if (api_.thread_current != nullptr) {
        thread_ = api_.thread_current();
        if (thread_ != nullptr) {
            return;
        }
    }
    if (api_.domain_get == nullptr || api_.thread_attach == nullptr) {
        return;
    }
    void* domain = api_.domain_get();
    if (domain != nullptr) {
        thread_ = api_.thread_attach(domain);
        owns_attachment_ = thread_ != nullptr;
    }
}

Il2CppAttachedThread::~Il2CppAttachedThread() {
    if (owns_attachment_ && thread_ != nullptr && api_.thread_detach != nullptr) {
        api_.thread_detach(thread_);
    }
}

bool Il2CppAttachedThread::attached() const {
    return thread_ != nullptr;
}

GcHandle::GcHandle(const Il2CppApi& api, void* object, bool pinned) : api_(&api) {
    if (object != nullptr && api_->gchandle_new != nullptr) {
        handle_ = api_->gchandle_new(object, pinned);
    }
}

GcHandle::~GcHandle() {
    Reset();
}

GcHandle::GcHandle(GcHandle&& other) noexcept : api_(other.api_), handle_(other.handle_) {
    other.api_ = nullptr;
    other.handle_ = 0;
}

GcHandle& GcHandle::operator=(GcHandle&& other) noexcept {
    if (this != &other) {
        Reset();
        api_ = other.api_;
        handle_ = other.handle_;
        other.api_ = nullptr;
        other.handle_ = 0;
    }
    return *this;
}

bool GcHandle::valid() const {
    return handle_ != 0;
}

void GcHandle::Reset() {
    if (api_ != nullptr && handle_ != 0 && api_->gchandle_free != nullptr) {
        api_->gchandle_free(handle_);
    }
    handle_ = 0;
    api_ = nullptr;
}

Il2CppRuntime::Il2CppRuntime(const Il2CppApi& api) : api_(api) {}

std::optional<void*> Il2CppRuntime::FindImage(
    const std::vector<std::string_view>& assemblies) const {
    if (api_.domain_get == nullptr || api_.domain_assembly_open == nullptr ||
        api_.assembly_get_image == nullptr) {
        return std::nullopt;
    }
    void* domain = api_.domain_get();
    if (domain == nullptr) {
        return std::nullopt;
    }
    for (const std::string_view assembly_name : assemblies) {
        const std::string name(assembly_name);
        void* assembly = api_.domain_assembly_open(domain, name.c_str());
        if (assembly == nullptr) {
            continue;
        }
        void* image = api_.assembly_get_image(assembly);
        if (image != nullptr) {
            return image;
        }
    }
    return std::nullopt;
}

std::optional<void*> Il2CppRuntime::FindClass(
    void* image, const std::vector<std::string_view>& namespaces, std::string_view name) const {
    if (image == nullptr || api_.class_from_name == nullptr) {
        return std::nullopt;
    }
    const std::string class_name(name);
    for (const std::string_view namespace_name : namespaces) {
        const std::string name_space(namespace_name);
        void* klass = api_.class_from_name(image, name_space.c_str(), class_name.c_str());
        if (klass != nullptr) {
            return klass;
        }
    }
    return std::nullopt;
}

std::optional<void*> Il2CppRuntime::FindNestedClass(void* klass, std::string_view name) const {
    if (klass == nullptr || api_.class_get_nested_types == nullptr ||
        api_.class_get_name == nullptr) {
        return std::nullopt;
    }
    void* iterator = nullptr;
    while (void* nested = api_.class_get_nested_types(klass, &iterator)) {
        if (IsMatchingName(api_.class_get_name(nested), name)) {
            return nested;
        }
    }
    return std::nullopt;
}

std::optional<void*> Il2CppRuntime::FindMethod(void* klass, std::string_view name,
                                                std::uint32_t parameter_count) const {
    if (klass == nullptr || api_.class_get_method_from_name == nullptr) {
        return std::nullopt;
    }
    const std::string method_name(name);
    void* method = api_.class_get_method_from_name(klass, method_name.c_str(),
                                                    static_cast<int>(parameter_count));
    if (method == nullptr || api_.method_get_param_count == nullptr ||
        api_.method_get_param_count(method) != parameter_count || !MethodCode(method).has_value()) {
        return std::nullopt;
    }
    return method;
}

std::optional<void*> Il2CppRuntime::FindMethodByFirstParameter(
    void* klass, std::string_view name, std::uint32_t parameter_count,
    std::string_view first_parameter_type) const {
    if (klass == nullptr || api_.class_get_methods == nullptr || api_.method_get_name == nullptr ||
        api_.method_get_param_count == nullptr || api_.method_get_param == nullptr ||
        api_.type_get_name == nullptr || api_.free_memory == nullptr) {
        return std::nullopt;
    }

    void* iterator = nullptr;
    while (void* method = api_.class_get_methods(klass, &iterator)) {
        if (!IsMatchingName(api_.method_get_name(method), name) ||
            api_.method_get_param_count(method) != parameter_count ||
            !MethodCode(method).has_value()) {
            continue;
        }
        const void* first_parameter = api_.method_get_param(method, 0);
        char* parameter_name = first_parameter == nullptr ? nullptr : api_.type_get_name(first_parameter);
        const bool matches = parameter_name != nullptr &&
            std::string_view(parameter_name) == first_parameter_type;
        if (parameter_name != nullptr) {
            api_.free_memory(parameter_name);
        }
        if (matches) {
            return method;
        }
    }
    return std::nullopt;
}
std::optional<void*> Il2CppRuntime::FindField(void* klass, std::string_view name) const {
    if (klass == nullptr || api_.class_get_fields == nullptr || api_.field_get_name == nullptr) {
        return std::nullopt;
    }
    void* iterator = nullptr;
    while (void* field = api_.class_get_fields(klass, &iterator)) {
        if (IsMatchingName(api_.field_get_name(field), name)) {
            return field;
        }
    }
    return std::nullopt;
}

std::vector<Il2CppRuntime::InstanceField> Il2CppRuntime::InstanceFields(void* klass) const {
    std::vector<InstanceField> fields;
    if (klass == nullptr || api_.class_get_fields == nullptr || api_.field_get_name == nullptr ||
        api_.field_get_offset == nullptr || api_.field_get_type == nullptr ||
        api_.class_from_type == nullptr || api_.class_is_valuetype == nullptr ||
        api_.class_value_size == nullptr) {
        return fields;
    }

    void* iterator = nullptr;
    while (void* field = api_.class_get_fields(klass, &iterator)) {
        const char* name = api_.field_get_name(field);
        const std::int32_t offset = api_.field_get_offset(field);
        const void* type = api_.field_get_type(field);
        void* field_class = type == nullptr ? nullptr : api_.class_from_type(type);
        if (name == nullptr || offset < 0 || field_class == nullptr) {
            continue;
        }
        const bool is_reference = !api_.class_is_valuetype(field_class);
        std::size_t value_size = sizeof(void*);
        if (!is_reference) {
            std::uint32_t alignment = 0;
            const std::int32_t size = api_.class_value_size(field_class, &alignment);
            if (size <= 0 || size > 64) {
                continue;
            }
            value_size = static_cast<std::size_t>(size);
        }
        fields.push_back({name, field, offset, value_size, is_reference});
    }
    return fields;
}
std::optional<std::int32_t> Il2CppRuntime::FieldOffset(void* klass, std::string_view name) const {
    const auto field = FindField(klass, name);
    if (!field.has_value() || api_.field_get_offset == nullptr) {
        return std::nullopt;
    }
    const std::int32_t offset = api_.field_get_offset(*field);
    return offset >= 0 ? std::optional<std::int32_t>(offset) : std::nullopt;
}

bool Il2CppRuntime::SetFieldValue(void* object, void* klass, std::string_view name,
                                  void* value) const {
    const auto field = FindField(klass, name);
    if (object == nullptr || !field.has_value() || api_.field_set_value == nullptr) {
        return false;
    }
    api_.field_set_value(object, *field, &value);
    return true;
}

std::optional<void*> Il2CppRuntime::NewArray(void* element_class, std::size_t length) const {
    if (element_class == nullptr || api_.array_new == nullptr) {
        return std::nullopt;
    }
    void* array = api_.array_new(element_class, length);
    return array == nullptr ? std::nullopt : std::optional<void*>(array);
}

std::optional<void*> Il2CppRuntime::NewObject(void* klass) const {
    if (klass == nullptr || api_.object_new == nullptr) {
        return std::nullopt;
    }
    void* object = api_.object_new(klass);
    return object == nullptr ? std::nullopt : std::optional<void*>(object);
}

std::optional<void*> Il2CppRuntime::NewObjectForField(
    void* owner_class, std::string_view field_name) const {
    const auto field = FindField(owner_class, field_name);
    if (!field.has_value() || api_.field_get_type == nullptr ||
        api_.class_from_type == nullptr) {
        return std::nullopt;
    }
    const void* type = api_.field_get_type(*field);
    void* klass = type == nullptr ? nullptr : api_.class_from_type(type);
    return NewObject(klass);
}

std::optional<void*> Il2CppRuntime::NewString(std::string_view text) const {
    if (api_.string_new == nullptr) {
        return std::nullopt;
    }
    const std::string value(text);
    void* object = api_.string_new(value.c_str());
    return object == nullptr ? std::nullopt : std::optional<void*>(object);
}

std::optional<void*> Il2CppRuntime::ValueBox(void* klass, void* value) const {
    if (klass == nullptr || value == nullptr || api_.value_box == nullptr) {
        return std::nullopt;
    }
    void* boxed = api_.value_box(klass, value);
    return boxed == nullptr ? std::nullopt : std::optional<void*>(boxed);
}

GcHandle Il2CppRuntime::Retain(void* object, bool pinned) const {
    return GcHandle(api_, object, pinned);
}

std::optional<void*> Il2CppRuntime::StaticFieldValue(void* klass, std::string_view name) const {
    const auto field = FindField(klass, name);
    if (!field.has_value() || api_.field_static_get_value == nullptr) {
        return std::nullopt;
    }
    void* value = nullptr;
    api_.field_static_get_value(*field, &value);
    return value == nullptr ? std::nullopt : std::optional<void*>(value);
}

std::optional<void*> Il2CppRuntime::InstanceFieldValue(void* object,
                                                       std::string_view name) const {
    if (object == nullptr || api_.object_get_class == nullptr) {
        return std::nullopt;
    }
    void* klass = api_.object_get_class(object);
    const auto field = FindField(klass, name);
    if (!field.has_value() || api_.field_get_offset == nullptr) {
        return std::nullopt;
    }
    const std::int32_t offset = api_.field_get_offset(*field);
    if (offset < 0) {
        return std::nullopt;
    }
    return *reinterpret_cast<void**>(reinterpret_cast<std::byte*>(object) + offset);
}

std::optional<void*> Il2CppRuntime::ObjectClass(void* object) const {
    if (object == nullptr || api_.object_get_class == nullptr) {
        return std::nullopt;
    }
    void* klass = api_.object_get_class(object);
    return klass == nullptr ? std::nullopt : std::optional<void*>(klass);
}

std::optional<void*> Il2CppRuntime::ObjectType(void* object) const {
    if (api_.class_get_type == nullptr) {
        return std::nullopt;
    }
    const auto klass = ObjectClass(object);
    if (!klass.has_value()) {
        return std::nullopt;
    }
    const void* type = api_.class_get_type(*klass);
    return type == nullptr ? std::nullopt :
        std::optional<void*>(const_cast<void*>(type));
}

std::optional<void*> Il2CppRuntime::ManagedType(void* object) const {
    if (api_.type_get_object == nullptr) {
        return std::nullopt;
    }
    const auto type = ObjectType(object);
    if (!type.has_value()) {
        return std::nullopt;
    }
    void* managed_type = api_.type_get_object(*type);
    return managed_type == nullptr ? std::nullopt : std::optional<void*>(managed_type);
}

std::optional<void*> Il2CppRuntime::Unbox(void* object) const {
    if (object == nullptr || api_.object_unbox == nullptr) {
        return std::nullopt;
    }
    void* value = api_.object_unbox(object);
    return value == nullptr ? std::nullopt : std::optional<void*>(value);
}

std::optional<const void*> Il2CppRuntime::ClassType(void* klass) const {
    if (klass == nullptr || api_.class_get_type == nullptr) {
        return std::nullopt;
    }
    const void* type = api_.class_get_type(klass);
    return type == nullptr ? std::nullopt : std::optional<const void*>(type);
}

std::optional<void*> Il2CppRuntime::Invoke(void* method, void* instance, void** parameters) const {
    void* exception = nullptr;
    return Invoke(method, instance, parameters, &exception);
}

std::optional<void*> Il2CppRuntime::Invoke(void* method, void* instance, void** parameters,
                                           void** exception) const {
    if (method == nullptr || api_.runtime_invoke == nullptr) {
        return std::nullopt;
    }
    void* local_exception = nullptr;
    void** exception_out = exception == nullptr ? &local_exception : exception;
    *exception_out = nullptr;
    void* result = api_.runtime_invoke(method, instance, parameters, exception_out);
    return *exception_out == nullptr && result != nullptr ?
        std::optional<void*>(result) : std::nullopt;
}

bool Il2CppRuntime::InvokeVoid(void* method, void* instance, void** parameters) const {
    if (method == nullptr || api_.runtime_invoke == nullptr) {
        return false;
    }
    void* exception = nullptr;
    api_.runtime_invoke(method, instance, parameters, &exception);
    return exception == nullptr;
}

std::optional<void*> Il2CppRuntime::MethodCode(void* method) const {
    if (method == nullptr) {
        return std::nullopt;
    }
    auto* raw = reinterpret_cast<std::byte*>(method);
    void* code = *reinterpret_cast<void**>(raw + kMethodCodePointerOffset);
    return IsExecutableCode(code) ? std::optional<void*>(code) : std::nullopt;
}

bool Il2CppRuntime::IsExecutableCode(void* address) const {
    if (address == nullptr) {
        return false;
    }
    ExecutableSearch search{reinterpret_cast<std::uintptr_t>(address), false};
    dl_iterate_phdr(FindExecutableSegment, &search);
    return search.found;
}

}  // namespace modloader
