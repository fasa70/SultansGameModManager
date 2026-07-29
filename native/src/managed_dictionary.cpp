#include "modloader/managed_dictionary.h"

namespace modloader {

ManagedIntDictionary::ManagedIntDictionary(const Il2CppRuntime& runtime) : runtime_(runtime) {}

bool ManagedIntDictionary::Probe(void* dictionary) {
    if (dictionary == nullptr) {
        return false;
    }
    const auto klass = runtime_.ObjectClass(dictionary);
    if (!klass.has_value()) {
        return false;
    }

    dictionary_ = dictionary;
    contains_ = runtime_.FindMethod(*klass, "ContainsKey", 1).value_or(nullptr);
    get_item_ = runtime_.FindMethod(*klass, "get_Item", 1).value_or(nullptr);
    set_item_ = runtime_.FindMethod(*klass, "set_Item", 2).value_or(nullptr);
    remove_ = runtime_.FindMethod(*klass, "Remove", 1).value_or(nullptr);
    return contains_ != nullptr && get_item_ != nullptr && set_item_ != nullptr && remove_ != nullptr;
}

std::optional<void*> ManagedIntDictionary::Get(std::int32_t key) const {
    if (dictionary_ == nullptr || get_item_ == nullptr) {
        return std::nullopt;
    }
    void* parameters[] = {&key};
    return runtime_.Invoke(get_item_, dictionary_, parameters);
}

bool ManagedIntDictionary::TryContains(std::int32_t key, bool* contains) const {
    if (dictionary_ == nullptr || contains_ == nullptr || contains == nullptr) {
        return false;
    }
    void* parameters[] = {&key};
    const auto result = runtime_.Invoke(contains_, dictionary_, parameters);
    if (!result.has_value()) {
        return false;
    }
    const auto value = runtime_.Unbox(*result);
    if (!value.has_value()) {
        return false;
    }
    *contains = *reinterpret_cast<const bool*>(*value);
    return true;
}

bool ManagedIntDictionary::Contains(std::int32_t key) const {
    bool contains = false;
    return TryContains(key, &contains) && contains;
}

bool ManagedIntDictionary::Set(std::int32_t key, void* value) const {
    if (dictionary_ == nullptr || set_item_ == nullptr || value == nullptr) {
        return false;
    }
    void* parameters[] = {&key, value};
    return runtime_.InvokeVoid(set_item_, dictionary_, parameters);
}

bool ManagedIntDictionary::Remove(std::int32_t key) const {
    if (dictionary_ == nullptr || remove_ == nullptr) {
        return false;
    }
    void* parameters[] = {&key};
    const auto result = runtime_.Invoke(remove_, dictionary_, parameters);
    if (!result.has_value()) {
        return false;
    }
    const auto value = runtime_.Unbox(*result);
    return value.has_value() && *reinterpret_cast<const bool*>(*value);
}

ManagedStringDictionary::ManagedStringDictionary(const Il2CppRuntime& runtime)
    : runtime_(runtime) {}

bool ManagedStringDictionary::Probe(void* dictionary) {
    const auto klass = runtime_.ObjectClass(dictionary);
    if (!klass.has_value()) {
        return false;
    }
    dictionary_ = dictionary;
    contains_ = runtime_.FindMethod(*klass, "ContainsKey", 1).value_or(nullptr);
    get_item_ = runtime_.FindMethod(*klass, "get_Item", 1).value_or(nullptr);
    set_item_ = runtime_.FindMethod(*klass, "set_Item", 2).value_or(nullptr);
    remove_ = runtime_.FindMethod(*klass, "Remove", 1).value_or(nullptr);
    return contains_ != nullptr && get_item_ != nullptr && set_item_ != nullptr &&
        remove_ != nullptr;
}

std::optional<void*> ManagedStringDictionary::NewKey(std::string_view key,
                                                      GcHandle* handle) const {
    if (handle == nullptr) {
        return std::nullopt;
    }
    const std::string text(key);
    const auto object = runtime_.NewString(text);
    if (!object.has_value()) {
        return std::nullopt;
    }
    *handle = runtime_.Retain(*object, false);
    return handle->valid() ? object : std::nullopt;
}

std::optional<void*> ManagedStringDictionary::Get(std::string_view key) const {
    if (dictionary_ == nullptr || get_item_ == nullptr) {
        return std::nullopt;
    }
    GcHandle handle;
    const auto managed_key = NewKey(key, &handle);
    if (!managed_key.has_value()) {
        return std::nullopt;
    }
    void* parameters[] = {*managed_key};
    return runtime_.Invoke(get_item_, dictionary_, parameters);
}

bool ManagedStringDictionary::TryContains(std::string_view key, bool* contains) const {
    if (dictionary_ == nullptr || contains_ == nullptr || contains == nullptr) {
        return false;
    }
    GcHandle handle;
    const auto managed_key = NewKey(key, &handle);
    if (!managed_key.has_value()) {
        return false;
    }
    void* parameters[] = {*managed_key};
    const auto result = runtime_.Invoke(contains_, dictionary_, parameters);
    if (!result.has_value()) {
        return false;
    }
    const auto value = runtime_.Unbox(*result);
    if (!value.has_value()) {
        return false;
    }
    *contains = *reinterpret_cast<const bool*>(*value);
    return true;
}

bool ManagedStringDictionary::Set(std::string_view key, void* value) const {
    if (dictionary_ == nullptr || set_item_ == nullptr || value == nullptr) {
        return false;
    }
    GcHandle handle;
    const auto managed_key = NewKey(key, &handle);
    if (!managed_key.has_value()) {
        return false;
    }
    void* parameters[] = {*managed_key, value};
    return runtime_.InvokeVoid(set_item_, dictionary_, parameters);
}

bool ManagedStringDictionary::Remove(std::string_view key) const {
    if (dictionary_ == nullptr || remove_ == nullptr) {
        return false;
    }
    GcHandle handle;
    const auto managed_key = NewKey(key, &handle);
    if (!managed_key.has_value()) {
        return false;
    }
    void* parameters[] = {*managed_key};
    const auto result = runtime_.Invoke(remove_, dictionary_, parameters);
    if (!result.has_value()) {
        return false;
    }
    const auto value = runtime_.Unbox(*result);
    return value.has_value() && *reinterpret_cast<const bool*>(*value);
}

}  // namespace modloader
