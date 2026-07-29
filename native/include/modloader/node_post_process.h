#pragma once

#include "modloader/config_pipeline.h"

#include <cstddef>
#include <vector>

namespace modloader {

enum class NodePrepareStage {
    kReady,
    kInvalidArgument,
    kProbeFailed,
    kRiteSlots,
    kRiteTagTranslation,
    kEventTimingIdentify,
    kEventSettlementArray,
    kEventSettlementUpdate,
    kEventTimingScan,
};

struct NodePrepareStats {
    NodePrepareStage stage = NodePrepareStage::kReady;
    std::size_t primary_settlements = 0;
    std::size_t secondary_settlements = 0;
    std::size_t tertiary_settlements = 0;
    std::size_t normal_settlements = 0;
    std::size_t cached_settlements = 0;
    std::size_t filled_slot_icons = 0;
    std::size_t translated_tags = 0;
    std::size_t timings = 0;
    bool has_rite_settlement = false;

    bool ok() const { return stage == NodePrepareStage::kReady; }
};

class NodePostProcessor {
  public:
    explicit NodePostProcessor(LoaderRuntimeContext& context);

    bool Probe();
    NodePrepareStats PrepareRite(void* node, std::vector<GcHandle>* retained) const;
    NodePrepareStats PrepareEvent(void* node, std::vector<GcHandle>* retained) const;

  private:
    LoaderRuntimeContext& context_;
    void* get_need_types_ = nullptr;
    void* translate_tag_ = nullptr;
    void* settlement_class_ = nullptr;
};

}  // namespace modloader
