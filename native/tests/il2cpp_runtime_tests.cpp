#include "modloader/il2cpp_runtime.h"

#include <cstdlib>
#include <cstring>
#include <iostream>
#include <string>
#include <utility>
#include <vector>

namespace {

struct FakeMethod {
    void* code = nullptr;
    const char* name = nullptr;
    std::vector<const char*> parameters;
    std::uint32_t flags = 0;
};

struct FakeField {
    const char* name = nullptr;
    std::int32_t offset = -1;
    std::uint32_t flags = 0;
    bool is_value_type = false;
};

struct FakeMetadata {
    std::vector<FakeMethod*> methods;
    std::vector<FakeField*> fields;
};

FakeMetadata* g_metadata = nullptr;
std::size_t g_allocations = 0;
std::size_t g_frees = 0;
int failures = 0;

void Check(bool condition, const char* message) {
    if (!condition) {
        std::cerr << "FAIL: " << message << '\n';
        ++failures;
    }
}

std::size_t NextIndex(void** iterator) {
    return reinterpret_cast<std::size_t>(*iterator);
}

void Advance(void** iterator, std::size_t index) {
    *iterator = reinterpret_cast<void*>(index + 1);
}

void* GetMethods(void*, void** iterator) {
    const std::size_t index = NextIndex(iterator);
    if (g_metadata == nullptr || index >= g_metadata->methods.size()) {
        return nullptr;
    }
    Advance(iterator, index);
    return g_metadata->methods[index];
}

const char* MethodName(const void* method) {
    return static_cast<const FakeMethod*>(method)->name;
}

std::uint32_t MethodParameterCount(const void* method) {
    return static_cast<std::uint32_t>(
        static_cast<const FakeMethod*>(method)->parameters.size());
}

const void* MethodParameter(const void* method, std::uint32_t index) {
    const auto& parameters = static_cast<const FakeMethod*>(method)->parameters;
    return index < parameters.size() ? parameters[index] : nullptr;
}

std::uint32_t MethodFlags(const void* method, std::uint32_t* implementation_flags) {
    if (implementation_flags != nullptr) {
        *implementation_flags = 0;
    }
    return static_cast<const FakeMethod*>(method)->flags;
}

char* TypeName(const void* type) {
    if (type == nullptr) {
        return nullptr;
    }
    const auto* value = static_cast<const char*>(type);
    const std::size_t length = std::strlen(value);
    auto* copy = static_cast<char*>(std::malloc(length + 1));
    if (copy != nullptr) {
        std::memcpy(copy, value, length + 1);
        ++g_allocations;
    }
    return copy;
}

void FreeMemory(void* memory) {
    if (memory != nullptr) {
        ++g_frees;
        std::free(memory);
    }
}

void* GetFields(void*, void** iterator) {
    const std::size_t index = NextIndex(iterator);
    if (g_metadata == nullptr || index >= g_metadata->fields.size()) {
        return nullptr;
    }
    Advance(iterator, index);
    return g_metadata->fields[index];
}

const char* FieldName(void* field) {
    return static_cast<FakeField*>(field)->name;
}

const void* FieldType(void* field) {
    return field;
}

std::int32_t FieldOffset(void* field) {
    return static_cast<FakeField*>(field)->offset;
}

std::uint32_t FieldFlags(void* field) {
    return static_cast<FakeField*>(field)->flags;
}

void* ClassFromType(const void* type) {
    return const_cast<void*>(type);
}

bool ClassIsValueType(void* klass) {
    return static_cast<FakeField*>(klass)->is_value_type;
}

void ExecutableStub() {}

modloader::Il2CppApi MakeApi() {
    modloader::Il2CppApi api;
    api.class_get_methods = GetMethods;
    api.method_get_name = MethodName;
    api.method_get_param_count = MethodParameterCount;
    api.method_get_param = MethodParameter;
    api.method_get_flags = MethodFlags;
    api.type_get_name = TypeName;
    api.free_memory = FreeMemory;
    api.class_get_fields = GetFields;
    api.field_get_name = FieldName;
    api.field_get_type = FieldType;
    api.field_get_offset = FieldOffset;
    api.field_get_flags = FieldFlags;
    api.class_from_type = ClassFromType;
    api.class_is_valuetype = ClassIsValueType;
    return api;
}

void ResetAllocationCounts() {
    g_allocations = 0;
    g_frees = 0;
}

void TestExactUniqueSetup() {
    FakeMethod exact{reinterpret_cast<void*>(ExecutableStub), "Setup",
                     {"ModNode", "ModPanelController"}};
    FakeMethod qualified{reinterpret_cast<void*>(ExecutableStub), "Setup",
                         {"Il2Cpp.ModNode", "Il2Cpp.ModPanelController"}};
    FakeMetadata metadata{{&qualified, &exact}, {}};
    g_metadata = &metadata;
    ResetAllocationCounts();

    const modloader::Il2CppRuntime runtime(MakeApi());
    const auto method = runtime.FindMethodByParameterTypes(
        &metadata, "Setup", {"ModNode", "ModPanelController"});
    Check(method.has_value() && *method == &exact,
          "exact unqualified Setup signature must resolve uniquely");
    Check(g_allocations == g_frees,
          "all exact signature type names must be released");

    const auto reversed = runtime.FindMethodByParameterTypes(
        &metadata, "Setup", {"ModPanelController", "ModNode"});
    Check(!reversed.has_value(), "reversed Setup parameter order must reject");
    const auto wrong_count = runtime.FindMethodByParameterTypes(
        &metadata, "Setup", {"ModNode"});
    Check(!wrong_count.has_value(), "wrong Setup parameter count must reject");
}

void TestDuplicateSetupRejects() {
    FakeMethod first{reinterpret_cast<void*>(ExecutableStub), "Setup",
                     {"ModNode", "ModPanelController"}};
    FakeMethod second{reinterpret_cast<void*>(ExecutableStub), "Setup",
                      {"ModNode", "ModPanelController"}};
    FakeMetadata metadata{{&first, &second}, {}};
    g_metadata = &metadata;
    ResetAllocationCounts();

    const modloader::Il2CppRuntime runtime(MakeApi());
    Check(!runtime.FindMethodByParameterTypes(
               &metadata, "Setup", {"ModNode", "ModPanelController"}).has_value(),
          "duplicate exact Setup signatures must reject");
    Check(g_allocations == g_frees,
          "duplicate signature type names must be released");
}

void TestUniqueMethodFlags() {
    constexpr std::uint32_t kMethodAttributeStatic = 0x0010;
    FakeMethod instance{reinterpret_cast<void*>(ExecutableStub), "LoadSprite",
                        {"System.String"}, 0};
    FakeMethod static_method{reinterpret_cast<void*>(ExecutableStub), "GetTexture",
                             {"System.String"}, kMethodAttributeStatic};
    FakeMethod static_implementation{
        reinterpret_cast<void*>(ExecutableStub), "GetTexture",
        {"System.String", "System.Boolean"}, kMethodAttributeStatic};
    FakeMethod wrong_static{reinterpret_cast<void*>(ExecutableStub), "GetTexture",
                            {"System.Int32"}, kMethodAttributeStatic};
    FakeMetadata metadata{
        {&instance, &static_method, &static_implementation, &wrong_static}, {}};
    g_metadata = &metadata;
    ResetAllocationCounts();

    const modloader::Il2CppRuntime runtime(MakeApi());
    Check(runtime.FindUniqueMethod(
              &metadata, "LoadSprite", {"System.String"}, false) == &instance,
          "unique instance String method must resolve");
    Check(runtime.FindUniqueMethod(
              &metadata, "GetTexture", {"System.String"}, true) == &static_method,
          "unique static String method must resolve");
    Check(runtime.FindUniqueMethod(
              &metadata, "GetTexture", {"System.String", "System.Boolean"},
              true) == &static_implementation,
          "unique static String Boolean implementation must resolve");
    Check(!runtime.FindUniqueMethod(
               &metadata, "GetTexture", {"System.String"}, false).has_value(),
          "static method must reject an instance requirement");

    FakeMethod duplicate{reinterpret_cast<void*>(ExecutableStub), "GetTexture",
                         {"System.String"}, kMethodAttributeStatic};
    metadata.methods.push_back(&duplicate);
    Check(!runtime.FindUniqueMethod(
               &metadata, "GetTexture", {"System.String"}, true).has_value(),
          "duplicate exact static methods must reject");
    Check(g_allocations == g_frees,
          "unique method signature names must be released");

    modloader::Il2CppApi api_without_flags = MakeApi();
    api_without_flags.method_get_flags = nullptr;
    const modloader::Il2CppRuntime runtime_without_flags(api_without_flags);
    Check(!runtime_without_flags.FindUniqueMethod(
               &metadata, "GetTexture", {"System.String"}, true).has_value(),
          "missing method flags metadata must fail closed");
}

void TestMetadataDescription() {
    FakeMethod exact{reinterpret_cast<void*>(ExecutableStub), "Setup",
                     {"ModNode", "ModPanelController"}};
    FakeMethod unknown{reinterpret_cast<void*>(ExecutableStub), "Setup",
                       {"Secret.Namespace.Node", "Secret.Namespace.Panel"}};
    FakeField mods{"mods", 0x28, 0, false};
    FakeField backing{"<mods>k__BackingField", 0x30, 0, false};
    FakeField native{"NativeFieldInfoPtr_mods", 0x38, 0, false};
    FakeMetadata metadata{{&exact, &unknown}, {&mods, &backing, &native}};
    g_metadata = &metadata;
    ResetAllocationCounts();

    const modloader::Il2CppRuntime runtime(MakeApi());
    const auto candidates = runtime.DescribeMetadata(
        &metadata, "Setup", &metadata, "mods", 8);
    Check(candidates.api_available && !candidates.truncated,
          "metadata API must report an available complete scan");
    Check(candidates.methods.size() == 2 &&
              candidates.methods[0].shape == "expected_node,expected_panel" &&
              candidates.methods[1].shape == "unknown,unknown",
          "metadata method shapes must use only fixed classifications");
    Check(candidates.fields.size() == 3 &&
              candidates.fields[0].key == "expected_mods" &&
              candidates.fields[1].key == "backing_mods" &&
              candidates.fields[2].key == "native_field_info_mods",
          "metadata fields must use stable fixed classifications");
    for (const auto& candidate : candidates.methods) {
        Check(candidate.shape.find("Secret") == std::string::npos,
              "metadata method shape must not leak raw type names");
    }
    Check(g_allocations == g_frees,
          "described metadata type names must be released");

    const auto truncated = runtime.DescribeMetadata(
        &metadata, "Setup", &metadata, "mods", 1);
    Check(truncated.truncated && truncated.methods.size() == 1 &&
              truncated.fields.size() <= 1,
          "metadata scan must enforce the fixed candidate limit");
    const auto wrong_field = runtime.DescribeMetadata(
        &metadata, "Setup", &metadata, "<mods>k__BackingField", 8);
    Check(wrong_field.api_available && wrong_field.methods.empty() &&
              wrong_field.fields.empty(),
          "metadata description must reject a non-profile field target");
}

void TestReferenceInstanceFieldGate() {
    constexpr std::uint32_t kFieldAttributeStatic = 0x0010;
    FakeField reference{"mods", 0x28, 0, false};
    FakeMetadata metadata{{}, {&reference}};
    g_metadata = &metadata;
    const modloader::Il2CppRuntime runtime(MakeApi());
    Check(runtime.ReferenceInstanceFieldOffset(&metadata, "mods") == 0x28,
          "reference instance mods field must pass the ABI gate");

    reference.is_value_type = true;
    Check(!runtime.ReferenceInstanceFieldOffset(&metadata, "mods").has_value(),
          "value-type mods field must fail closed");
    reference.is_value_type = false;
    reference.flags = kFieldAttributeStatic;
    Check(!runtime.ReferenceInstanceFieldOffset(&metadata, "mods").has_value(),
          "static mods field must fail closed");
    reference.flags = 0;
    reference.offset = -1;
    Check(!runtime.ReferenceInstanceFieldOffset(&metadata, "mods").has_value(),
          "invalid mods field offset must fail closed");
    reference.offset = 0x28;
    const auto missing_flags_api = [] {
        modloader::Il2CppApi api = MakeApi();
        api.field_get_flags = nullptr;
        return api;
    }();
    const modloader::Il2CppRuntime runtime_without_flags(missing_flags_api);
    Check(!runtime_without_flags.ReferenceInstanceFieldOffset(
               &metadata, "mods").has_value(),
          "missing field flags metadata must fail closed");
    const auto missing_type_api = [] {
        modloader::Il2CppApi api = MakeApi();
        api.field_get_type = nullptr;
        return api;
    }();
    const modloader::Il2CppRuntime runtime_without_type(missing_type_api);
    Check(!runtime_without_type.ReferenceInstanceFieldOffset(
               &metadata, "mods").has_value(),
          "missing field type metadata must fail closed");
}

}  // namespace

int main() {
    TestExactUniqueSetup();
    TestDuplicateSetupRejects();
    TestUniqueMethodFlags();
    TestMetadataDescription();
    TestReferenceInstanceFieldGate();
    if (failures != 0) {
        std::cerr << failures << " test assertion(s) failed\n";
        return EXIT_FAILURE;
    }
    std::cout << "modloader_il2cpp_runtime_tests passed\n";
    return EXIT_SUCCESS;
}
