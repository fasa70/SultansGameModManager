#include "modloader/json_bridge.h"

#include <cstring>
#include <string>
#include <vector>

namespace modloader {
namespace {

constexpr std::size_t kArrayDataOffset = 0x20;

using ReaderConstructor = void (*)(void*, void*, const void*);
using NodeReader = void* (*)(void*, void*, const void*);

const std::vector<std::string_view> kDataNodeAssemblies = {
    "DataNode.Core.dll",
    "Il2CppDataNode.Core.dll",
};

const std::vector<std::string_view> kCorlibAssemblies = {
    "mscorlib.dll",
    "Il2Cppmscorlib.dll",
};

const std::vector<std::string_view> kJsonNamespaces = {
    "DataNode.Json",
    "Il2CppDataNode.Json",
};

}  // namespace

JsonBridge::JsonBridge(const Il2CppRuntime& runtime) : runtime_(runtime) {}

bool JsonBridge::Probe() {
    const auto data_node_image = runtime_.FindImage(kDataNodeAssemblies);
    const auto corlib_image = runtime_.FindImage(kCorlibAssemblies);
    if (!data_node_image.has_value() || !corlib_image.has_value()) {
        return false;
    }

    const auto serializer = runtime_.FindClass(*data_node_image, kJsonNamespaces, "JsonSerializer");
    const auto byte_class = runtime_.FindClass(*corlib_image, {"System"}, "Byte");
    if (!serializer.has_value() || !byte_class.has_value()) {
        return false;
    }

    serializer_class_ = *serializer;
    byte_class_ = *byte_class;
    return FindByteArrayDeserialize().has_value();
}

std::optional<void*> JsonBridge::FindByteArrayDeserialize() const {
    if (serializer_class_ == nullptr || byte_class_ == nullptr) {
        return std::nullopt;
    }
    return runtime_.FindMethodByFirstParameter(
        serializer_class_, "DeserializeByType", 3,
        "System.Byte[]");
}

std::optional<void*> JsonBridge::ManagedTypeOf(void* target) const {
    return runtime_.ManagedType(target);
}

std::optional<void*> JsonBridge::DeserializeInto(void* target, std::string_view json) const {
    const auto method = FindByteArrayDeserialize();
    if (!method.has_value() || target == nullptr || json.empty()) {
        return std::nullopt;
    }

    const auto byte_array = runtime_.NewArray(byte_class_, json.size());
    if (!byte_array.has_value()) {
        return std::nullopt;
    }
    GcHandle byte_array_handle = runtime_.Retain(*byte_array, false);
    if (!byte_array_handle.valid()) {
        return std::nullopt;
    }
    std::memcpy(reinterpret_cast<std::byte*>(*byte_array) + kArrayDataOffset,
                json.data(), json.size());

    const auto target_type = ManagedTypeOf(target);
    if (!target_type.has_value()) {
        return std::nullopt;
    }
    void* parameters[] = {*byte_array, *target_type, nullptr};
    return runtime_.Invoke(*method, nullptr, parameters);
}

std::optional<void*> JsonBridge::DeserializeNode(void* common_image,
                                                  std::string_view handler_name,
                                                  std::string_view json) const {
    if (common_image == nullptr || byte_class_ == nullptr || json.empty()) {
        return std::nullopt;
    }
    const auto data_node_image = runtime_.FindImage(kDataNodeAssemblies);
    if (!data_node_image.has_value()) {
        return std::nullopt;
    }
    const auto reader_class = runtime_.FindClass(*data_node_image, kJsonNamespaces, "JsonTokenReader");
    const auto handler_class = runtime_.FindClass(common_image, kJsonNamespaces, handler_name);
    if (!reader_class.has_value() || !handler_class.has_value()) {
        return std::nullopt;
    }
    const auto reader_ctor = runtime_.FindMethod(*reader_class, ".ctor", 1);
    const auto read = runtime_.FindMethod(*handler_class, "Read", 2);
    if (!reader_ctor.has_value() || !read.has_value()) {
        return std::nullopt;
    }

    const auto byte_array = runtime_.NewArray(byte_class_, json.size());
    if (!byte_array.has_value()) {
        return std::nullopt;
    }
    GcHandle byte_handle = runtime_.Retain(*byte_array, false);
    if (!byte_handle.valid()) {
        return std::nullopt;
    }
    std::memcpy(reinterpret_cast<std::byte*>(*byte_array) + kArrayDataOffset,
                json.data(), json.size());

    std::byte empty_reader[0x28]{};
    const auto boxed_reader = runtime_.ValueBox(*reader_class, empty_reader);
    if (!boxed_reader.has_value()) {
        return std::nullopt;
    }
    GcHandle reader_handle = runtime_.Retain(*boxed_reader, false);
    const auto reader_data = runtime_.Unbox(*boxed_reader);
    if (!reader_handle.valid() || !reader_data.has_value()) {
        return std::nullopt;
    }
    const auto reader_ctor_code = runtime_.MethodCode(*reader_ctor);
    const auto read_code = runtime_.MethodCode(*read);
    if (!reader_ctor_code.has_value() || !read_code.has_value()) {
        return std::nullopt;
    }
    reinterpret_cast<ReaderConstructor>(*reader_ctor_code)(
        *reader_data, *byte_array, *reader_ctor);
    void* result = reinterpret_cast<NodeReader>(*read_code)(
        *reader_data, nullptr, *read);
    return result == nullptr ? std::nullopt : std::optional<void*>(result);
}

}  // namespace modloader
