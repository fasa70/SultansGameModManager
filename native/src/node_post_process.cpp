#include "modloader/node_post_process.h"

#include "modloader/managed_dictionary.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <string_view>
#include <utility>
#include <vector>

namespace modloader {
namespace {

constexpr std::size_t kArrayLengthOffset = 0x18;
constexpr std::size_t kArrayDataOffset = 0x20;

std::size_t ArrayLength(void* array) {
    return array == nullptr ? 0 :
        *reinterpret_cast<const std::uint32_t*>(
            reinterpret_cast<const std::byte*>(array) + kArrayLengthOffset);
}

void* ArrayItem(void* array, std::size_t index) {
    return *reinterpret_cast<void**>(reinterpret_cast<std::byte*>(array) +
                                     kArrayDataOffset + index * sizeof(void*));
}

std::size_t NumberSettlements(void* array, std::int32_t type) {
    std::int32_t index = 1;
    for (std::size_t position = 0; position < ArrayLength(array); ++position) {
        void* settlement = ArrayItem(array, position);
        if (settlement == nullptr) {
            continue;
        }
        *reinterpret_cast<std::int32_t*>(reinterpret_cast<std::byte*>(settlement) + 0x44) = type;
        *reinterpret_cast<std::int32_t*>(reinterpret_cast<std::byte*>(settlement) + 0x48) = index++;
    }
    return static_cast<std::size_t>(index - 1);
}

bool Retain(const Il2CppRuntime& runtime, void* object, std::vector<GcHandle>* retained) {
    if (object == nullptr) {
        return true;
    }
    GcHandle handle = runtime.Retain(object, false);
    if (!handle.valid()) {
        return false;
    }
    retained->push_back(std::move(handle));
    return true;
}

bool EqualsManagedString(void* string, std::u16string_view expected) {
    if (string == nullptr) {
        return false;
    }
    const auto* bytes = reinterpret_cast<const std::byte*>(string);
    const auto length = *reinterpret_cast<const std::int32_t*>(bytes + 0x10);
    const auto* characters = reinterpret_cast<const char16_t*>(bytes + 0x14);
    return length == static_cast<std::int32_t>(expected.size()) &&
        std::u16string_view(characters, static_cast<std::size_t>(length)) == expected;
}

}  // namespace

NodePostProcessor::NodePostProcessor(LoaderRuntimeContext& context) : context_(context) {}

bool NodePostProcessor::Probe() {
    const auto condition_extensions = context_.runtime.FindClass(
        context_.core_image, {"", "Il2Cpp"}, "ConditionExtensions");
    const auto datapool_class = context_.runtime.ObjectClass(context_.datapool);
    const auto rite_class = context_.runtime.FindClass(
        context_.common_image, {"", "Il2Cpp"}, "RiteNode");
    if (!condition_extensions.has_value() || !datapool_class.has_value() ||
        !rite_class.has_value()) {
        return false;
    }
    get_need_types_ = context_.runtime.FindMethod(
        *condition_extensions, "GetNeedTypes", 1).value_or(nullptr);
    translate_tag_ = context_.runtime.FindMethod(
        *datapool_class, "TranslateTag", 1).value_or(nullptr);
    settlement_class_ = context_.runtime.FindNestedClass(
        *rite_class, "Settlement").value_or(nullptr);
    return get_need_types_ != nullptr && translate_tag_ != nullptr &&
        settlement_class_ != nullptr;
}

NodePrepareStats NodePostProcessor::PrepareRite(void* node,
                                                std::vector<GcHandle>* retained) const {
    NodePrepareStats stats;
    if (node == nullptr || retained == nullptr) {
        stats.stage = NodePrepareStage::kInvalidArgument;
        return stats;
    }
    if (get_need_types_ == nullptr || translate_tag_ == nullptr) {
        stats.stage = NodePrepareStage::kProbeFailed;
        return stats;
    }
    auto* bytes = reinterpret_cast<std::byte*>(node);
    stats.primary_settlements = NumberSettlements(*reinterpret_cast<void**>(bytes + 0x98), 1);
    stats.secondary_settlements = NumberSettlements(*reinterpret_cast<void**>(bytes + 0xa0), 2);
    stats.tertiary_settlements = NumberSettlements(*reinterpret_cast<void**>(bytes + 0xa8), 3);

    void* slots = *reinterpret_cast<void**>(bytes + 0xb0);
    if (slots != nullptr) {
        ManagedStringDictionary dictionary(context_.runtime);
        if (!dictionary.Probe(slots)) {
            stats.stage = NodePrepareStage::kRiteSlots;
            return stats;
        }
        constexpr std::array<std::string_view, 7> keys = {
            "s1", "s2", "s3", "s4", "s5", "s6", "s7",
        };
        for (const std::string_view key : keys) {
            bool contains = false;
            if (!dictionary.TryContains(key, &contains)) {
                stats.stage = NodePrepareStage::kRiteSlots;
                return stats;
            }
            if (!contains) {
                continue;
            }
            const auto slot = dictionary.Get(key);
            if (!slot.has_value()) {
                stats.stage = NodePrepareStage::kRiteSlots;
                return stats;
            }
            auto* slot_bytes = reinterpret_cast<std::byte*>(*slot);
            void* tips_icon = *reinterpret_cast<void**>(slot_bytes + 0x38);
            if (tips_icon != nullptr && ArrayLength(tips_icon) != 0) {
                continue;
            }
            void* condition = *reinterpret_cast<void**>(slot_bytes + 0x18);
            void* parameters[] = {condition};
            const auto need_types = context_.runtime.Invoke(
                get_need_types_, nullptr, parameters);
            if (need_types.has_value()) {
                if (!Retain(context_.runtime, *need_types, retained)) {
                    stats.stage = NodePrepareStage::kRiteSlots;
                    return stats;
                }
                *reinterpret_cast<void**>(slot_bytes + 0x38) = *need_types;
                ++stats.filled_slot_icons;
            }
        }
    }

    void* tag_tips_up = *reinterpret_cast<void**>(bytes + 0x70);
    if (tag_tips_up == nullptr) {
        return stats;
    }
    void* tips = *reinterpret_cast<void**>(
        reinterpret_cast<std::byte*>(tag_tips_up) + 0x10);
    if (tips == nullptr) {
        return stats;
    }
    for (std::size_t index = 0; index < ArrayLength(tips); ++index) {
        void* original = ArrayItem(tips, index);
        if (original == nullptr) {
            continue;
        }
        void* parameters[] = {original};
        const auto translated = context_.runtime.Invoke(
            translate_tag_, context_.datapool, parameters);
        if (translated.has_value()) {
            if (!Retain(context_.runtime, *translated, retained)) {
                stats.stage = NodePrepareStage::kRiteTagTranslation;
                return stats;
            }
            *reinterpret_cast<void**>(reinterpret_cast<std::byte*>(tips) +
                                      kArrayDataOffset + index * sizeof(void*)) = *translated;
            ++stats.translated_tags;
        }
    }
    return stats;
}

NodePrepareStats NodePostProcessor::PrepareEvent(void* node,
                                                 std::vector<GcHandle>* retained) const {
    NodePrepareStats stats;
    if (node == nullptr || retained == nullptr) {
        stats.stage = NodePrepareStage::kInvalidArgument;
        return stats;
    }
    if (settlement_class_ == nullptr) {
        stats.stage = NodePrepareStage::kProbeFailed;
        return stats;
    }
    auto* bytes = reinterpret_cast<std::byte*>(node);
    std::int32_t event_id = *reinterpret_cast<std::int32_t*>(bytes + 0x18);
    void* timings = *reinterpret_cast<void**>(bytes + 0x28);
    if (timings != nullptr) {
        const auto timings_class = context_.runtime.ObjectClass(timings);
        if (!timings_class.has_value()) {
            stats.stage = NodePrepareStage::kEventTimingIdentify;
            return stats;
        }
        const auto set_identify = context_.runtime.FindMethod(*timings_class, "SetIdentify", 1);
        if (!set_identify.has_value()) {
            stats.stage = NodePrepareStage::kEventTimingIdentify;
            return stats;
        }
        void* parameters[] = {&event_id};
        if (!context_.runtime.InvokeVoid(*set_identify, timings, parameters)) {
            stats.stage = NodePrepareStage::kEventTimingIdentify;
            return stats;
        }
    }

    void* source = *reinterpret_cast<void**>(bytes + 0x40);
    std::vector<void*> normal;
    std::vector<void*> cached;
    for (std::size_t index = 0; index < ArrayLength(source); ++index) {
        void* settlement = ArrayItem(source, index);
        if (settlement == nullptr) {
            stats.stage = NodePrepareStage::kEventSettlementArray;
            return stats;
        }
        const auto cache = *reinterpret_cast<std::int32_t*>(
            reinterpret_cast<std::byte*>(settlement) + 0x40);
        (cache == 0 ? normal : cached).push_back(settlement);
    }
    stats.normal_settlements = normal.size();
    stats.cached_settlements = cached.size();

    const auto normal_array = context_.runtime.NewArray(settlement_class_, normal.size());
    const auto cached_array = context_.runtime.NewArray(settlement_class_, cached.size());
    if (!normal_array.has_value() || !cached_array.has_value()) {
        stats.stage = NodePrepareStage::kEventSettlementArray;
        return stats;
    }
    for (std::size_t index = 0; index < normal.size(); ++index) {
        *reinterpret_cast<void**>(reinterpret_cast<std::byte*>(*normal_array) +
                                  kArrayDataOffset + index * sizeof(void*)) = normal[index];
    }
    for (std::size_t index = 0; index < cached.size(); ++index) {
        *reinterpret_cast<void**>(reinterpret_cast<std::byte*>(*cached_array) +
                                  kArrayDataOffset + index * sizeof(void*)) = cached[index];
    }
    if (!Retain(context_.runtime, *normal_array, retained) ||
        !Retain(context_.runtime, *cached_array, retained)) {
        stats.stage = NodePrepareStage::kEventSettlementArray;
        return stats;
    }

    const auto event_class = context_.runtime.ObjectClass(node);
    const auto update = event_class.has_value() ?
        context_.runtime.FindMethod(*event_class, "UpdateSettlement", 2) : std::nullopt;
    if (!update.has_value()) {
        stats.stage = NodePrepareStage::kEventSettlementUpdate;
        return stats;
    }
    void* update_parameters[] = {*normal_array, *cached_array};
    if (!context_.runtime.InvokeVoid(*update, node, update_parameters)) {
        stats.stage = NodePrepareStage::kEventSettlementUpdate;
        return stats;
    }

    if (timings == nullptr) {
        return stats;
    }
    const auto timings_class = context_.runtime.ObjectClass(timings);
    const auto get_timings = timings_class.has_value() ?
        context_.runtime.FindMethod(*timings_class, "get_timings", 0) : std::nullopt;
    if (!get_timings.has_value()) {
        stats.stage = NodePrepareStage::kEventTimingScan;
        return stats;
    }
    const auto timing_list = context_.runtime.Invoke(*get_timings, timings, nullptr);
    if (!timing_list.has_value()) {
        stats.stage = NodePrepareStage::kEventTimingScan;
        return stats;
    }
    auto* list_bytes = reinterpret_cast<std::byte*>(*timing_list);
    void* list_items = *reinterpret_cast<void**>(list_bytes + 0x10);
    const std::int32_t count = *reinterpret_cast<std::int32_t*>(list_bytes + 0x18);
    stats.timings = count < 0 ? 0 : static_cast<std::size_t>(count);
    for (std::int32_t index = 0; index < count; ++index) {
        void* timing = ArrayItem(list_items, static_cast<std::size_t>(index));
        const auto timing_class = context_.runtime.ObjectClass(timing);
        const auto get_name = timing_class.has_value() ?
            context_.runtime.FindMethod(*timing_class, "get_Timing", 0) : std::nullopt;
        if (!get_name.has_value()) {
            continue;
        }
        const auto name = context_.runtime.Invoke(*get_name, timing, nullptr);
        if (!name.has_value() || !EqualsManagedString(*name, u"rite_settlement")) {
            continue;
        }
        stats.has_rite_settlement = true;
        void* settlements = *reinterpret_cast<void**>(bytes + 0x40);
        for (std::size_t settlement_index = 0;
             settlement_index < ArrayLength(settlements); ++settlement_index) {
            auto* settlement = reinterpret_cast<std::byte*>(
                ArrayItem(settlements, settlement_index));
            if (settlement == nullptr) {
                stats.stage = NodePrepareStage::kEventTimingScan;
                return stats;
            }
            *reinterpret_cast<std::int32_t*>(settlement + 0x44) = event_id;
            *reinterpret_cast<std::int32_t*>(settlement + 0x48) =
                static_cast<std::int32_t>(settlement_index + 1);
        }
        break;
    }
    return stats;
}

}  // namespace modloader
