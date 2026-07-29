#pragma once

#include "modloader/runtime_state.h"

#include <cstddef>
#include <cstdint>

namespace modloader {

using Il2CppDomainGet = void* (*)();
using Il2CppThreadAttach = void* (*)(void* domain);
using Il2CppThreadCurrent = void* (*)();
using Il2CppThreadDetach = void (*)(void* thread);
using Il2CppDomainAssemblyOpen = void* (*)(void* domain, const char* name);
using Il2CppAssemblyGetImage = void* (*)(void* assembly);
using Il2CppClassFromName = void* (*)(void* image, const char* namespaze, const char* name);
using Il2CppClassGetName = const char* (*)(void* klass);
using Il2CppClassGetType = const void* (*)(void* klass);
using Il2CppClassIsValueType = bool (*)(void* klass);
using Il2CppClassValueSize = std::int32_t (*)(void* klass, std::uint32_t* align);
using Il2CppClassGetMethodFromName = void* (*)(void* klass, const char* name, int parameter_count);
using Il2CppClassGetMethods = void* (*)(void* klass, void** iterator);
using Il2CppClassGetNestedTypes = void* (*)(void* klass, void** iterator);
using Il2CppMethodGetName = const char* (*)(const void* method);
using Il2CppMethodGetParamCount = std::uint32_t (*)(const void* method);
using Il2CppMethodGetParam = const void* (*)(const void* method, std::uint32_t index);
using Il2CppTypeGetName = char* (*)(const void* type);
using Il2CppTypeGetObject = void* (*)(const void* type);
using Il2CppFree = void (*)(void* memory);
using Il2CppClassGetFields = void* (*)(void* klass, void** iterator);
using Il2CppFieldGetName = const char* (*)(void* field);
using Il2CppFieldGetType = const void* (*)(void* field);
using Il2CppFieldGetOffset = std::int32_t (*)(void* field);
using Il2CppFieldSetValue = void (*)(void* object, void* field, void* value);
using Il2CppFieldStaticGetValue = void (*)(void* field, void* value);
using Il2CppRuntimeClassInit = void (*)(void* klass);
using Il2CppRuntimeInvoke = void* (*)(const void* method, void* object, void** parameters,
                                      void** exception);
using Il2CppArrayNew = void* (*)(void* element_class, std::size_t length);
using Il2CppObjectNew = void* (*)(void* klass);
using Il2CppClassFromType = void* (*)(const void* type);
using Il2CppObjectGetClass = void* (*)(void* object);
using Il2CppStringNew = void* (*)(const char* text);
using Il2CppValueBox = void* (*)(void* klass, void* value);
using Il2CppObjectUnbox = void* (*)(void* object);
using Il2CppGcHandleNew = std::uint32_t (*)(void* object, bool pinned);
using Il2CppGcHandleFree = void (*)(std::uint32_t handle);

struct Il2CppApi {
    void* library_handle = nullptr;
    void* image_base = nullptr;
    std::size_t image_size = 0;
    Il2CppDomainGet domain_get = nullptr;
    Il2CppThreadAttach thread_attach = nullptr;
    Il2CppThreadCurrent thread_current = nullptr;
    Il2CppThreadDetach thread_detach = nullptr;
    Il2CppDomainAssemblyOpen domain_assembly_open = nullptr;
    Il2CppAssemblyGetImage assembly_get_image = nullptr;
    Il2CppClassFromName class_from_name = nullptr;
    Il2CppClassGetName class_get_name = nullptr;
    Il2CppClassGetType class_get_type = nullptr;
    Il2CppClassIsValueType class_is_valuetype = nullptr;
    Il2CppClassValueSize class_value_size = nullptr;
    Il2CppClassGetMethodFromName class_get_method_from_name = nullptr;
    Il2CppClassGetMethods class_get_methods = nullptr;
    Il2CppClassGetNestedTypes class_get_nested_types = nullptr;
    Il2CppMethodGetName method_get_name = nullptr;
    Il2CppMethodGetParamCount method_get_param_count = nullptr;
    Il2CppMethodGetParam method_get_param = nullptr;
    Il2CppTypeGetName type_get_name = nullptr;
    Il2CppTypeGetObject type_get_object = nullptr;
    Il2CppFree free_memory = nullptr;
    Il2CppClassGetFields class_get_fields = nullptr;
    Il2CppFieldGetName field_get_name = nullptr;
    Il2CppFieldGetType field_get_type = nullptr;
    Il2CppFieldGetOffset field_get_offset = nullptr;
    Il2CppFieldSetValue field_set_value = nullptr;
    Il2CppFieldStaticGetValue field_static_get_value = nullptr;
    Il2CppRuntimeClassInit runtime_class_init = nullptr;
    Il2CppRuntimeInvoke runtime_invoke = nullptr;
    Il2CppArrayNew array_new = nullptr;
    Il2CppObjectNew object_new = nullptr;
    Il2CppClassFromType class_from_type = nullptr;
    Il2CppObjectGetClass object_get_class = nullptr;
    Il2CppStringNew string_new = nullptr;
    Il2CppValueBox value_box = nullptr;
    Il2CppObjectUnbox object_unbox = nullptr;
    Il2CppGcHandleNew gchandle_new = nullptr;
    Il2CppGcHandleFree gchandle_free = nullptr;
};

Il2CppResolver& GetAndroidIl2CppResolver();
const Il2CppApi* GetResolvedIl2CppApi();

}  // namespace modloader
