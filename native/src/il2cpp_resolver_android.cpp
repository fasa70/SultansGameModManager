#include "modloader/il2cpp_api.h"

#include <dlfcn.h>
#include <link.h>

#include <cstring>
#include <mutex>
#include <string>

namespace modloader {
namespace {

constexpr const char* kIl2CppLibrary = "libil2cpp.so";

struct ModuleSearch {
    std::string path;
    std::uintptr_t base = 0;
    std::size_t size = 0;
};

bool HasLibraryName(const char* path) {
    if (path == nullptr) {
        return false;
    }
    const char* name = std::strrchr(path, '/');
    name = name == nullptr ? path : name + 1;
    return std::strcmp(name, kIl2CppLibrary) == 0;
}

int FindIl2Cpp(struct dl_phdr_info* info, size_t, void* data) {
    if (!HasLibraryName(info->dlpi_name)) {
        return 0;
    }

    auto* search = static_cast<ModuleSearch*>(data);
    search->path = info->dlpi_name;
    search->base = static_cast<std::uintptr_t>(info->dlpi_addr);
    std::uintptr_t image_end = search->base;
    for (ElfW(Half) index = 0; index < info->dlpi_phnum; ++index) {
        const ElfW(Phdr)& header = info->dlpi_phdr[index];
        if (header.p_type != PT_LOAD) {
            continue;
        }
        const std::uintptr_t end = search->base + header.p_vaddr + header.p_memsz;
        if (end > image_end) {
            image_end = end;
        }
    }
    search->size = image_end - search->base;
    return 1;
}

template <typename Function>
Function Resolve(void* handle, const char* name) {
    return reinterpret_cast<Function>(dlsym(handle, name));
}

class AndroidIl2CppResolver final : public Il2CppResolver {
  public:
    ResolveStatus TryResolve() override {
        std::lock_guard<std::mutex> lock(mutex_);
        if (ready_) {
            return ResolveStatus::kReady;
        }

        if (api_.library_handle == nullptr) {
            ModuleSearch search;
            dl_iterate_phdr(FindIl2Cpp, &search);
            if (search.path.empty() || search.base == 0 || search.size == 0) {
                return ResolveStatus::kNotLoaded;
            }

            api_.library_handle = dlopen(search.path.c_str(), RTLD_NOW | RTLD_NOLOAD);
            if (api_.library_handle == nullptr) {
                api_.library_handle = dlopen(kIl2CppLibrary, RTLD_NOW | RTLD_NOLOAD);
            }
            if (api_.library_handle == nullptr) {
                return ResolveStatus::kNoLoadHandle;
            }
            api_.image_base = reinterpret_cast<void*>(search.base);
            api_.image_size = search.size;
        }

        api_.domain_get = Resolve<Il2CppDomainGet>(api_.library_handle, "il2cpp_domain_get");
        api_.thread_attach = Resolve<Il2CppThreadAttach>(api_.library_handle, "il2cpp_thread_attach");
        api_.thread_current = Resolve<Il2CppThreadCurrent>(api_.library_handle, "il2cpp_thread_current");
        api_.thread_detach = Resolve<Il2CppThreadDetach>(api_.library_handle, "il2cpp_thread_detach");
        api_.domain_assembly_open = Resolve<Il2CppDomainAssemblyOpen>(
            api_.library_handle, "il2cpp_domain_assembly_open");
        api_.assembly_get_image = Resolve<Il2CppAssemblyGetImage>(
            api_.library_handle, "il2cpp_assembly_get_image");
        api_.class_from_name = Resolve<Il2CppClassFromName>(
            api_.library_handle, "il2cpp_class_from_name");
        api_.class_get_name = Resolve<Il2CppClassGetName>(
            api_.library_handle, "il2cpp_class_get_name");
        api_.class_get_type = Resolve<Il2CppClassGetType>(
            api_.library_handle, "il2cpp_class_get_type");
        api_.class_is_valuetype = Resolve<Il2CppClassIsValueType>(
            api_.library_handle, "il2cpp_class_is_valuetype");
        api_.class_value_size = Resolve<Il2CppClassValueSize>(
            api_.library_handle, "il2cpp_class_value_size");
        api_.class_get_method_from_name = Resolve<Il2CppClassGetMethodFromName>(
            api_.library_handle, "il2cpp_class_get_method_from_name");
        api_.class_get_methods = Resolve<Il2CppClassGetMethods>(
            api_.library_handle, "il2cpp_class_get_methods");
        api_.class_get_nested_types = Resolve<Il2CppClassGetNestedTypes>(
            api_.library_handle, "il2cpp_class_get_nested_types");
        api_.method_get_name = Resolve<Il2CppMethodGetName>(
            api_.library_handle, "il2cpp_method_get_name");
        api_.method_get_param_count = Resolve<Il2CppMethodGetParamCount>(
            api_.library_handle, "il2cpp_method_get_param_count");
        api_.method_get_param = Resolve<Il2CppMethodGetParam>(
            api_.library_handle, "il2cpp_method_get_param");
        api_.type_get_name = Resolve<Il2CppTypeGetName>(
            api_.library_handle, "il2cpp_type_get_name");
        api_.type_get_object = Resolve<Il2CppTypeGetObject>(
            api_.library_handle, "il2cpp_type_get_object");
        api_.free_memory = Resolve<Il2CppFree>(api_.library_handle, "il2cpp_free");
        api_.class_get_fields = Resolve<Il2CppClassGetFields>(
            api_.library_handle, "il2cpp_class_get_fields");
        api_.field_get_name = Resolve<Il2CppFieldGetName>(
            api_.library_handle, "il2cpp_field_get_name");
        api_.field_get_type = Resolve<Il2CppFieldGetType>(
            api_.library_handle, "il2cpp_field_get_type");
        api_.field_get_offset = Resolve<Il2CppFieldGetOffset>(
            api_.library_handle, "il2cpp_field_get_offset");
        api_.field_set_value = Resolve<Il2CppFieldSetValue>(
            api_.library_handle, "il2cpp_field_set_value");
        api_.field_static_get_value = Resolve<Il2CppFieldStaticGetValue>(
            api_.library_handle, "il2cpp_field_static_get_value");
        api_.runtime_class_init = Resolve<Il2CppRuntimeClassInit>(
            api_.library_handle, "il2cpp_runtime_class_init");
        api_.runtime_invoke = Resolve<Il2CppRuntimeInvoke>(
            api_.library_handle, "il2cpp_runtime_invoke");
        api_.array_new = Resolve<Il2CppArrayNew>(api_.library_handle, "il2cpp_array_new");
        api_.array_length = Resolve<Il2CppArrayLength>(api_.library_handle, "il2cpp_array_length");
        api_.object_new = Resolve<Il2CppObjectNew>(api_.library_handle, "il2cpp_object_new");
        api_.class_from_type = Resolve<Il2CppClassFromType>(
            api_.library_handle, "il2cpp_class_from_type");
        api_.object_get_class = Resolve<Il2CppObjectGetClass>(
            api_.library_handle, "il2cpp_object_get_class");
        api_.string_new = Resolve<Il2CppStringNew>(api_.library_handle, "il2cpp_string_new");
        api_.value_box = Resolve<Il2CppValueBox>(api_.library_handle, "il2cpp_value_box");
        api_.object_unbox = Resolve<Il2CppObjectUnbox>(
            api_.library_handle, "il2cpp_object_unbox");
        api_.gchandle_new = Resolve<Il2CppGcHandleNew>(
            api_.library_handle, "il2cpp_gchandle_new");
        api_.gchandle_free = Resolve<Il2CppGcHandleFree>(
            api_.library_handle, "il2cpp_gchandle_free");
        api_.resolve_icall = Resolve<Il2CppResolveIcall>(
            api_.library_handle, "il2cpp_resolve_icall");
        if (api_.domain_get == nullptr || api_.thread_attach == nullptr ||
            api_.thread_current == nullptr || api_.thread_detach == nullptr ||
            api_.domain_assembly_open == nullptr ||
            api_.assembly_get_image == nullptr || api_.class_from_name == nullptr ||
            api_.class_get_name == nullptr || api_.class_get_type == nullptr ||
            api_.class_get_method_from_name == nullptr || api_.class_get_methods == nullptr ||
            api_.class_get_nested_types == nullptr ||
            api_.method_get_name == nullptr || api_.method_get_param_count == nullptr || api_.method_get_param == nullptr ||
            api_.type_get_name == nullptr || api_.type_get_object == nullptr ||
            api_.free_memory == nullptr ||
            api_.class_get_fields == nullptr ||
            api_.field_get_name == nullptr || api_.field_get_offset == nullptr ||
            api_.field_set_value == nullptr ||
            api_.field_static_get_value == nullptr || api_.runtime_class_init == nullptr ||
            api_.runtime_invoke == nullptr || api_.array_new == nullptr ||
            api_.array_length == nullptr || api_.resolve_icall == nullptr ||
            api_.object_new == nullptr || api_.object_get_class == nullptr ||
            api_.string_new == nullptr || api_.value_box == nullptr ||
            api_.object_unbox == nullptr || api_.gchandle_new == nullptr ||
            api_.gchandle_free == nullptr) {
            return ResolveStatus::kRequiredSymbolMissing;
        }

        ready_ = true;
        return ResolveStatus::kReady;
    }

    const Il2CppApi* api() const {
        return ready_ ? &api_ : nullptr;
    }

  private:
    mutable std::mutex mutex_;
    Il2CppApi api_;
    bool ready_ = false;
};

AndroidIl2CppResolver& Resolver() {
    static AndroidIl2CppResolver resolver;
    return resolver;
}

}  // namespace

Il2CppResolver& GetAndroidIl2CppResolver() {
    return Resolver();
}

const Il2CppApi* GetResolvedIl2CppApi() {
    return Resolver().api();
}

}  // namespace modloader
