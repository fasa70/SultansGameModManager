#include "modloader/mod_hooks.h"

#include "modloader/android_log.h"
#include "modloader/backend_route.h"
#include "modloader/bootstrap_notify.h"
#include "modloader/game_profile.h"
#include "modloader/hook_engine.h"
#include "modloader/il2cpp_runtime.h"
#include "modloader/mod_lifecycle.h"
#include "modloader/mod_root.h"
#include "modloader/native_mod_loader.h"
#include "modloader/official_observer_validation.h"
#include "modloader/official_resource_uri_hooks.h"
#include "modloader/resource_overrides.h"
#include "modloader/resource_hooks.h"
#include "modloader/mod_file_index.h"
#include "modloader/tmp_glyph_hooks.h"
#include "modloader/ui_reveal_hooks.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <thread>

namespace modloader {
namespace {

using LoadConfigFunction = void* (*)(void*, int, const void*);
using LoadGlobalModsFunction = void* (*)(void*, const void*);
using ModLoaderActiveModFunction =
    void (*)(void*, void*, void*, void*, const void*);
using ModLoaderRunFunction = void (*)(void*, void*, const void*);
using ModPanelVoidFunction = void (*)(void*, const void*);
using ModItemSetupFunction = void (*)(void*, void*, void*, const void*);
using LoadSingleFileFunction = void (*)(void*, void*, void*, void*, const void*);
using PostProcessFunction = void (*)(void*, const void*);
using LoadUserArchiveFunction = bool (*)(void*, std::int32_t, const void*);
using LoadUserArchiveDataFunction = void (*)(void*, const void*);
using RiteRenderInitFunction = void (*)(void*, void*, const void*);

HookEngine g_hooks;
HookEngine g_official_observer_hooks;
HookEngine g_official_compatibility_hooks;
HookEngine g_official_ui_hooks;
BackendRouteController g_backend_route;
std::unique_ptr<LifecycleGate> g_lifecycle;
std::mutex g_setup_mutex;
std::mutex g_config_hook_mutex;

enum class OfficialObserverState : std::uint8_t {
    kUnprepared,
    kInstalling,
    kReady,
    kRejected,
};

std::mutex g_official_observer_mutex;
OfficialObserverState g_official_observer_state =
    OfficialObserverState::kUnprepared;
std::mutex g_official_observer_publish_mutex;
std::atomic<LoadGlobalModsFunction> g_official_load_global_mods{nullptr};
std::atomic<ModLoaderActiveModFunction> g_official_mod_loader_active_mod{nullptr};
std::atomic<ModLoaderRunFunction> g_official_mod_loader_run{nullptr};
std::atomic<void*> g_official_datapool{nullptr};
std::atomic<bool> g_official_observer_active{false};

enum class OfficialUiObserverState : std::uint8_t {
    kUnprepared,
    kInstalling,
    kReady,
    kRejected,
};

HookEngine g_official_ui_observer_hooks;
std::mutex g_official_ui_observer_mutex;
OfficialUiObserverState g_official_ui_observer_state =
    OfficialUiObserverState::kUnprepared;
std::mutex g_official_ui_observer_publish_mutex;
std::atomic<ModPanelVoidFunction> g_official_panel_on_enable{nullptr};
std::atomic<ModPanelVoidFunction> g_official_panel_show_mods{nullptr};
std::atomic<ModPanelVoidFunction> g_official_panel_refresh_mods{nullptr};
std::atomic<ModItemSetupFunction> g_official_item_setup{nullptr};
std::atomic<std::int32_t> g_official_panel_mods_offset{-1};
std::atomic<bool> g_official_ui_observer_active{false};
std::atomic<bool> g_official_ui_metadata_diagnostic_attempted{false};
std::atomic<bool> g_official_compatibility_installed{false};
const Il2CppApi* g_api = nullptr;
RuntimeController* g_runtime = nullptr;
LoadConfigFunction g_load_config = nullptr;
LoadSingleFileFunction g_load_single_file = nullptr;
PostProcessFunction g_rite_post_process = nullptr;
PostProcessFunction g_event_post_process = nullptr;
LoadUserArchiveFunction g_load_user_archive = nullptr;
LoadUserArchiveDataFunction g_load_user_archive_data = nullptr;
RiteRenderInitFunction g_rite_render_init = nullptr;
void* g_datapool = nullptr;
void* g_get_rite_data = nullptr;
void* g_get_card_data = nullptr;
void* g_rite_data_setter = nullptr;
void* g_card_data_setter = nullptr;
std::int32_t g_rite_id_offset = -1;
std::int32_t g_rite_data_offset = -1;
std::int32_t g_rite_cards_offset = -1;
std::int32_t g_card_id_offset = -1;
std::int32_t g_card_data_offset = -1;
bool g_archive_hook_installed = false;
bool g_recovery_hook_installed = false;
bool g_post_process_hooks_installed = false;
std::mutex g_apply_mutex;
bool g_cards_applied = false;
bool g_upgrade_applied = false;
bool g_rite_applied = false;
bool g_event_applied = false;
bool g_remaining_applied = false;
ModApplySummary g_summary;

void LogOfficialDatapoolSnapshot(const char* point, void* datapool = nullptr) noexcept;
bool MatchesFingerprint(const CodeFingerprint& fingerprint);

void LogOfficialUiMetadataCandidates(
    const Il2CppRuntime& runtime, void* item_class, void* panel_class,
    const OfficialUiObserverMembers& members) {
    if ((members.item_setup && members.panel_mods) ||
        g_official_ui_metadata_diagnostic_attempted.exchange(true,
            std::memory_order_acq_rel)) {
        return;
    }
    constexpr std::size_t kCandidateLimit = 8;
    const auto candidates = runtime.DescribeMetadata(
        item_class, "Setup", panel_class, kOfficialUiPanelModsField, kCandidateLimit);
    const std::string reason = !members.item_setup && !members.panel_mods
        ? "item_setup|panel_mods"
        : (!members.item_setup ? "item_setup" : "panel_mods");
    std::string message = "official_ui_observer=metadata_candidates reason=" + reason +
        " api=" + (candidates.api_available ? "available" : "unavailable") +
        " setup_count=" + std::to_string(candidates.methods.size()) +
        " field_count=" + std::to_string(candidates.fields.size()) +
        " truncated=" + (candidates.truncated ? "1" : "0") + " setup_shapes=";
    if (candidates.methods.empty()) {
        message += "none";
    } else {
        for (std::size_t index = 0; index < candidates.methods.size(); ++index) {
            if (index != 0) message += '|';
            const auto& candidate = candidates.methods[index];
            message += std::to_string(candidate.parameter_count) + ":" + candidate.shape +
                ":" + (candidate.method_code_valid ? "valid" : "invalid");
        }
    }
    message += " field_shapes=";
    if (candidates.fields.empty()) {
        message += "none";
    } else {
        for (std::size_t index = 0; index < candidates.fields.size(); ++index) {
            if (index != 0) message += '|';
            const auto& candidate = candidates.fields[index];
            message += candidate.key + ":" +
                (candidate.offset_valid ? "valid" : "invalid") + ":" +
                (candidate.is_reference ? "reference" : "value_or_unknown");
        }
    }
    LogMessage(message.c_str());
}

void* StubPointer(void*) {
    return nullptr;
}

void StubVoid(void*) {}

void Schedule(std::chrono::milliseconds delay, std::function<void()> callback) {
    std::thread([delay, callback = std::move(callback)]() mutable {
        std::this_thread::sleep_for(delay);
        callback();
    }).detach();
}

void MergeSummary(const ModApplySummary& stage) {
    g_summary.discovered_mods = std::max(g_summary.discovered_mods, stage.discovered_mods);
    g_summary.discovered_config_files = std::max(
        g_summary.discovered_config_files, stage.discovered_config_files);
    g_summary.discovered_image_files = std::max(
        g_summary.discovered_image_files, stage.discovered_image_files);
    g_summary.discovered_audio_files = std::max(
        g_summary.discovered_audio_files, stage.discovered_audio_files);
    g_summary.rejected_entries = std::max(g_summary.rejected_entries, stage.rejected_entries);
    g_summary.skipped_unsupported_configs = std::max(
        g_summary.skipped_unsupported_configs, stage.skipped_unsupported_configs);
    g_summary.abi_ready = g_summary.abi_ready || stage.abi_ready;
    g_summary.json_bridge_ready = g_summary.json_bridge_ready || stage.json_bridge_ready;
    g_summary.card_dictionary_ready =
        g_summary.card_dictionary_ready || stage.card_dictionary_ready;
    g_summary.card_mods_discovered = std::max(
        g_summary.card_mods_discovered, stage.card_mods_discovered);
    g_summary.card_mods_committed += stage.card_mods_committed;
    g_summary.card_entries_committed += stage.card_entries_committed;
    g_summary.card_mods_failed += stage.card_mods_failed;
    g_summary.card_rollback_failed =
        g_summary.card_rollback_failed || stage.card_rollback_failed;
    g_summary.upgrade_mods += stage.upgrade_mods;
    g_summary.upgrade_committed += stage.upgrade_committed;
    g_summary.upgrade_entries += stage.upgrade_entries;
    g_summary.single_file_committed += stage.single_file_committed;
    g_summary.directory_committed += stage.directory_committed;
    g_summary.rite_committed += stage.rite_committed;
    g_summary.event_committed += stage.event_committed;
    g_summary.config_entries_committed += stage.config_entries_committed;
    g_summary.config_failed_transactions += stage.config_failed_transactions;
    g_summary.config_rollback_failed =
        g_summary.config_rollback_failed || stage.config_rollback_failed;
}

void ApplyStage(ModApplyStage stage, bool* applied) {
    if (g_backend_route.route() != BackendRoute::kStagedNative) {
        return;
    }
    std::lock_guard<std::mutex> lock(g_apply_mutex);
    if (*applied || g_api == nullptr) {
        return;
    }
    *applied = true;
    const std::string mod_root = GetModRoot();
    if (mod_root.empty()) {
        return;
    }
    MergeSummary(NativeModLoader(*g_api, mod_root).Apply(stage));
}

std::string ManagedString(void* object) {
    if (object == nullptr) {
        return {};
    }
    const auto* bytes = reinterpret_cast<const std::byte*>(object);
    const std::int32_t length = *reinterpret_cast<const std::int32_t*>(bytes + 0x10);
    if (length < 0) {
        return {};
    }
    const auto* text = reinterpret_cast<const char16_t*>(bytes + 0x14);
    std::string result;
    result.reserve(static_cast<std::size_t>(length));
    for (std::int32_t index = 0; index < length; ++index) {
        const std::uint32_t value = text[index];
        if (value <= 0x7fU) {
            result.push_back(static_cast<char>(value));
        } else if (value <= 0x7ffU) {
            result.push_back(static_cast<char>(0xc0U | (value >> 6U)));
            result.push_back(static_cast<char>(0x80U | (value & 0x3fU)));
        } else {
            result.push_back(static_cast<char>(0xe0U | (value >> 12U)));
            result.push_back(static_cast<char>(0x80U | ((value >> 6U) & 0x3fU)));
            result.push_back(static_cast<char>(0x80U | (value & 0x3fU)));
        }
    }
    return result;
}

void OnLoadSingleFile(void* instance, void* path, void* config_name,
                      void* config_value, const void* method) {
    g_load_single_file(instance, path, config_name, config_value, method);
    const std::string loaded_path = ManagedString(path);
    if (loaded_path == "config/cards.json") {
        ApplyStage(ModApplyStage::kCards, &g_cards_applied);
        LogMessage("lifecycle=cards_injected");
    } else if (loaded_path == "config/upgrade.json") {
        ApplyStage(ModApplyStage::kUpgrade, &g_upgrade_applied);
        LogMessage("lifecycle=upgrade_injected");
    }
}

void OnInjectionWindow(RuntimeController* runtime) {
    if (g_backend_route.route() != BackendRoute::kStagedNative) {
        return;
    }
    LogMessage("lifecycle=window_reached");
    const std::string mod_root = GetModRoot();
    if (g_api == nullptr || mod_root.empty()) {
        LogMessage("native_loader=skipped reason=missing_context");
    } else {
        ApplyStage(ModApplyStage::kCards, &g_cards_applied);
        ApplyStage(ModApplyStage::kUpgrade, &g_upgrade_applied);
        ApplyStage(ModApplyStage::kRite, &g_rite_applied);
        ApplyStage(ModApplyStage::kEvent, &g_event_applied);
        ApplyStage(ModApplyStage::kRemaining, &g_remaining_applied);
        const ModFileIndex resource_index = ScanModRoot(mod_root);
        const ResourceOverrideIndex resource_overrides = BuildResourceOverrideIndex(resource_index);
        for (const ResourceModStats& stats : resource_overrides.mod_stats) {
            char resource_message[256]{};
            std::snprintf(resource_message, sizeof(resource_message),
                          "resource_scan mod=%s image_files=%zu accepted=%zu rejected=%zu",
                          stats.mod_name.c_str(), stats.image_files,
                          stats.accepted_images, stats.rejected_images);
            LogMessage(resource_message);
        }
        for (const ResourceOverrideCollision& collision : resource_overrides.image_collisions) {
            const std::string resource_message = "resource_collision kind=image key=" +
                collision.key + " previous_mod=" + collision.previous.mod_name +
                " previous_file=" + collision.previous.relative_path +
                " winner_mod=" + collision.winner.mod_name +
                " winner_file=" + collision.winner.relative_path;
            LogMessage(resource_message.c_str());
        }
        const ResourceHookStats resource_hooks = InstallResourceHooks(
            *g_api, resource_overrides, &g_hooks);
        g_summary.resource_audio_preloads = resource_hooks.audio_preloads;
        g_summary.image_hooks_ready = resource_hooks.image_hooks_ready;
        g_summary.audio_hooks_ready = resource_hooks.audio_hooks_ready;
        char message[384]{};
        std::snprintf(message, sizeof(message),
                      "native_loader mods=%zu config=%zu image=%zu audio=%zu rejected=%zu "
                      "unsupported=%zu abi=%s json=%s card_dict=%s card_mods=%zu "
                      "card_committed=%zu card_entries=%zu card_failed=%zu rollback=%s",
                      g_summary.discovered_mods, g_summary.discovered_config_files,
                      g_summary.discovered_image_files, g_summary.discovered_audio_files,
                      g_summary.rejected_entries, g_summary.skipped_unsupported_configs,
                      g_summary.abi_ready ? "ready" : "unavailable",
                      g_summary.json_bridge_ready ? "ready" : "unavailable",
                      g_summary.card_dictionary_ready ? "ready" : "unavailable",
                      g_summary.card_mods_discovered, g_summary.card_mods_committed,
                      g_summary.card_entries_committed, g_summary.card_mods_failed,
                      g_summary.card_rollback_failed ? "failed" : "ok");
        LogMessage(message);
        std::snprintf(message, sizeof(message),
                      "native_pipeline upgrade=%zu/%zu upgrade_entries=%zu single=%zu "
                      "directory=%zu rite=%zu event=%zu entries=%zu failed=%zu rollback=%s "
                      "image_hooks=%s audio_hooks=%s audio_preloads=%zu image_collisions=%zu "
                      "resource_rejected=%zu mode=staged_native",
                      g_summary.upgrade_committed, g_summary.upgrade_mods,
                      g_summary.upgrade_entries, g_summary.single_file_committed,
                      g_summary.directory_committed, g_summary.rite_committed,
                      g_summary.event_committed, g_summary.config_entries_committed,
                      g_summary.config_failed_transactions,
                      g_summary.config_rollback_failed ? "failed" : "ok",
                      g_summary.image_hooks_ready ? "ready" : "unavailable",
                      g_summary.audio_hooks_ready ? "ready" : "unavailable",
                      g_summary.resource_audio_preloads, resource_hooks.image_collisions,
                      resource_hooks.rejected_resources);
        LogMessage(message);
    }
    g_backend_route.MarkReady(BackendRoute::kStagedNative);
    if (runtime->MarkReady()) {
        NotifyModsApplied(g_summary.discovered_mods);
    }
    LogState(runtime->state());
}

void OnRitePostProcess(void* instance, const void* method) {
    g_rite_post_process(instance, method);
    LogMessage("lifecycle=rite_postprocess_observed");
    g_lifecycle->ObserveRitePostProcess();
}

void OnEventPostProcess(void* instance, const void* method) {
    g_event_post_process(instance, method);
    LogMessage("lifecycle=event_postprocess_observed");
    g_lifecycle->ObserveEventPostProcess();
}

bool FindDatapoolMethods(const Il2CppApi& api, void** load_single_file,
                         void** rite, void** event) {
    Il2CppAttachedThread attached(api);
    if (!attached.attached()) {
        return false;
    }
    void* domain = api.domain_get();

    void* assembly = api.domain_assembly_open(domain, "Core.dll");
    if (assembly == nullptr) {
        assembly = api.domain_assembly_open(domain, "Il2CppCore.dll");
    }
    if (assembly == nullptr) {
        return false;
    }

    void* image = api.assembly_get_image(assembly);
    if (image == nullptr) {
        return false;
    }
    void* datapool = api.class_from_name(image, "", "Datapool");
    if (datapool == nullptr) {
        datapool = api.class_from_name(image, "Il2Cpp", "Datapool");
    }
    if (datapool == nullptr) {
        return false;
    }

    void* single_file_method = api.class_get_method_from_name(datapool, "LoadSingleFile", 2);
    void* rite_method = api.class_get_method_from_name(datapool, "LoadRitePostProcess", 0);
    void* event_method = api.class_get_method_from_name(datapool, "LoadEventPostProcess", 0);
    if (single_file_method == nullptr || rite_method == nullptr || event_method == nullptr) {
        return false;
    }

    *load_single_file = *reinterpret_cast<void**>(single_file_method);
    *rite = *reinterpret_cast<void**>(rite_method);
    *event = *reinterpret_cast<void**>(event_method);
    return *load_single_file != nullptr && *rite != nullptr && *event != nullptr;
}

bool InstallPostProcessHooks() {
    std::lock_guard<std::mutex> lock(g_setup_mutex);
    if (g_post_process_hooks_installed) {
        return true;
    }

    void* load_single_file = nullptr;
    void* rite_post_process = nullptr;
    void* event_post_process = nullptr;
    if (!FindDatapoolMethods(*g_api, &load_single_file,
                             &rite_post_process, &event_post_process)) {
        g_runtime->Fail(FailureCode::kIl2CppReflectionUnavailable);
        LogFailure(g_runtime->failure());
        return false;
    }

    void* original = nullptr;
    if (!g_hooks.Replace(load_single_file, reinterpret_cast<void*>(OnLoadSingleFile), &original)) {
        g_runtime->Fail(FailureCode::kHookInstallFailed);
        LogFailure(g_runtime->failure());
        return false;
    }
    g_load_single_file = reinterpret_cast<LoadSingleFileFunction>(original);
    if (!g_hooks.Replace(rite_post_process, reinterpret_cast<void*>(OnRitePostProcess), &original)) {
        g_runtime->Fail(FailureCode::kHookInstallFailed);
        LogFailure(g_runtime->failure());
        return false;
    }
    g_rite_post_process = reinterpret_cast<PostProcessFunction>(original);
    if (!g_hooks.Replace(event_post_process, reinterpret_cast<void*>(OnEventPostProcess), &original)) {
        g_hooks.Rollback();
        g_runtime->Fail(FailureCode::kHookInstallFailed);
        LogFailure(g_runtime->failure());
        return false;
    }
    g_event_post_process = reinterpret_cast<PostProcessFunction>(original);
    g_post_process_hooks_installed = true;
    LogMessage("hooks=post_process_installed");
    return true;
}

bool ContainsId(const std::vector<std::int32_t>& ids, std::int32_t id) {
    return std::find(ids.begin(), ids.end(), id) != ids.end();
}

void RebindRiteCards(void* rite, const CommittedModIds& committed,
                     const Il2CppRuntime& runtime) {
    if (g_rite_cards_offset < 0 || g_card_id_offset < 0 ||
        g_card_data_offset < 0 || g_get_card_data == nullptr ||
        g_card_data_setter == nullptr) {
        return;
    }
    void* cards = *reinterpret_cast<void**>(
        reinterpret_cast<std::byte*>(rite) + g_rite_cards_offset);
    if (cards == nullptr) {
        return;
    }
    auto* list_bytes = reinterpret_cast<std::byte*>(cards);
    void* items = *reinterpret_cast<void**>(list_bytes + 0x10);
    const std::int32_t count = *reinterpret_cast<std::int32_t*>(list_bytes + 0x18);
    if (items == nullptr || count < 0 || count > 256) {
        return;
    }
    std::size_t rebound = 0;
    for (std::int32_t index = 0; index < count; ++index) {
        void* card = *reinterpret_cast<void**>(
            reinterpret_cast<std::byte*>(items) + 0x20 +
            static_cast<std::size_t>(index) * sizeof(void*));
        if (card == nullptr) {
            continue;
        }
        std::int32_t id = *reinterpret_cast<std::int32_t*>(
            reinterpret_cast<std::byte*>(card) + g_card_id_offset);
        void* data = *reinterpret_cast<void**>(
            reinterpret_cast<std::byte*>(card) + g_card_data_offset);
        if (!ContainsId(committed.cards, id)) {
            continue;
        }
        void* getter_parameters[] = {&id};
        const auto canonical = runtime.Invoke(
            g_get_card_data, g_datapool, getter_parameters);
        if (!canonical.has_value() || data == *canonical) {
            continue;
        }
        void* setter_parameters[] = {*canonical};
        if (runtime.InvokeVoid(g_card_data_setter, card, setter_parameters)) {
            ++rebound;
        }
    }
    if (rebound != 0) {
        const std::string message = "recovery_rebind kind=card count=" +
            std::to_string(rebound);
        LogMessage(message.c_str());
    }
}

void OnRiteRenderInit(void* render, void* rite, const void* method) {
    if (rite == nullptr) {
        LogMessage("recovery_rebind render_init rite=null");
    } else if (g_api != nullptr && g_rite_id_offset >= 0 &&
               g_rite_data_offset >= 0 && g_get_rite_data != nullptr &&
               g_rite_data_setter != nullptr) {
        Il2CppRuntime runtime(*g_api);
        const CommittedModIds committed = GetCommittedModIds();
        std::int32_t id = *reinterpret_cast<std::int32_t*>(
            reinterpret_cast<std::byte*>(rite) + g_rite_id_offset);
        void* data = *reinterpret_cast<void**>(
            reinterpret_cast<std::byte*>(rite) + g_rite_data_offset);
        void* getter_parameters[] = {&id};
        const auto canonical = runtime.Invoke(
            g_get_rite_data, g_datapool, getter_parameters);
        const bool committed_id = ContainsId(committed.rites, id);
        const bool matches = canonical.has_value() && data == *canonical;
        const std::string entered = "recovery_rebind render_init id=" +
            std::to_string(id) + " data=" + (data == nullptr ? "null" : "set") +
            " canonical=" + (canonical.has_value() ?
                (*canonical == data ? "same" : "different") : "missing") +
            " committed=" + (committed_id ? "yes" : "no");
        LogMessage(entered.c_str());
        if (committed_id && !matches && canonical.has_value()) {
            void* setter_parameters[] = {*canonical};
            if (runtime.InvokeVoid(g_rite_data_setter, rite, setter_parameters)) {
                const std::string message = "recovery_rebind kind=rite id=" +
                    std::to_string(id) + " result=bound";
                LogMessage(message.c_str());
            } else {
                const std::string message = "recovery_rebind kind=rite id=" +
                    std::to_string(id) + " result=failed";
                LogMessage(message.c_str());
            }
        }
        RebindRiteCards(rite, committed, runtime);
    }
    g_rite_render_init(render, rite, method);
}

bool InstallRecoveryHook() {
    std::lock_guard<std::mutex> lock(g_setup_mutex);
    if (g_recovery_hook_installed) {
        return true;
    }
    Il2CppRuntime runtime(*g_api);
    const auto core = runtime.FindImage({"Core.dll", "Il2CppCore.dll"});
    const auto common = runtime.FindImage({"Common.dll", "Il2CppCommon.dll"});
    const auto game = runtime.FindImage({"Game.dll", "Il2CppGame.dll"});
    if (!core.has_value() || !common.has_value() || !game.has_value()) {
        LogMessage("recovery_rebind hook=unavailable reason=image");
        return false;
    }
    const auto application = runtime.FindClass(*core, {"", "Il2Cpp"}, "GameApplication");
    const auto datapool_class = runtime.FindClass(*core, {"", "Il2Cpp"}, "Datapool");
    const auto rite_class = runtime.FindClass(*common, {"", "Il2Cpp"}, "Rite");
    const auto card_class = runtime.FindClass(*common, {"", "Il2Cpp"}, "Card");
    const auto render_class = runtime.FindClass(*game, {"", "Il2Cpp"}, "RiteRender");
    if (!application.has_value() || !datapool_class.has_value() ||
        !rite_class.has_value() || !card_class.has_value() ||
        !render_class.has_value()) {
        LogMessage("recovery_rebind hook=unavailable reason=class");
        return false;
    }
    const auto datapool = runtime.StaticFieldValue(*application, "datapool");
    const auto get_rite = runtime.FindMethod(*datapool_class, "GetRiteData", 1);
    const auto get_card = runtime.FindMethod(*datapool_class, "GetCardData", 1);
    const auto set_rite = runtime.FindMethod(*rite_class, "set_data", 1);
    const auto set_card = runtime.FindMethod(*card_class, "set_data", 1);
    const auto rite_id = runtime.FieldOffset(*rite_class, "____id");
    const auto rite_data = runtime.FieldOffset(*rite_class, "<data>k__BackingField");
    const auto rite_cards = runtime.FieldOffset(*rite_class, "____cards");
    const auto card_id = runtime.FieldOffset(*card_class, "____id");
    const auto card_data = runtime.FieldOffset(*card_class, "<data>k__BackingField");
    const auto render_init = runtime.FindMethod(*render_class, "Init", 1);
    if (!datapool.has_value() || !get_rite.has_value() || !get_card.has_value() ||
        !set_rite.has_value() || !set_card.has_value() || !rite_id.has_value() ||
        !rite_data.has_value() || !rite_cards.has_value() || !card_id.has_value() ||
        !card_data.has_value() || !render_init.has_value()) {
        std::string message = "recovery_rebind hook=unavailable reason=member";
        message += " datapool=" + std::string(datapool.has_value() ? "ok" : "missing");
        message += " get_rite=" + std::string(get_rite.has_value() ? "ok" : "missing");
        message += " get_card=" + std::string(get_card.has_value() ? "ok" : "missing");
        message += " set_rite=" + std::string(set_rite.has_value() ? "ok" : "missing");
        message += " set_card=" + std::string(set_card.has_value() ? "ok" : "missing");
        message += " rite_id=" + std::string(rite_id.has_value() ? "ok" : "missing");
        message += " rite_data=" + std::string(rite_data.has_value() ? "ok" : "missing");
        message += " rite_cards=" + std::string(rite_cards.has_value() ? "ok" : "missing");
        message += " card_id=" + std::string(card_id.has_value() ? "ok" : "missing");
        message += " card_data=" + std::string(card_data.has_value() ? "ok" : "missing");
        message += " render_init=" + std::string(render_init.has_value() ? "ok" : "missing");
        LogMessage(message.c_str());
        return false;
    }
    const auto render_code = runtime.MethodCode(*render_init);
    void* original = nullptr;
    if (!render_code.has_value() ||
        !g_hooks.Replace(*render_code, reinterpret_cast<void*>(OnRiteRenderInit), &original)) {
        LogMessage("recovery_rebind hook=failed");
        return false;
    }
    g_rite_render_init = reinterpret_cast<RiteRenderInitFunction>(original);
    g_datapool = *datapool;
    g_get_rite_data = *get_rite;
    g_get_card_data = *get_card;
    g_rite_data_setter = *set_rite;
    g_card_data_setter = *set_card;
    g_rite_id_offset = *rite_id;
    g_rite_data_offset = *rite_data;
    g_rite_cards_offset = *rite_cards;
    g_card_id_offset = *card_id;
    g_card_data_offset = *card_data;
    g_recovery_hook_installed = true;
    LogMessage("recovery_rebind hook=installed");
    return true;
}

void OnLoadUserArchiveData(void* instance, const void* method) {
    LogMessage("recovery_audit archive_data=enter");
    g_load_user_archive_data(instance, method);
    LogMessage("recovery_audit archive_data=leave");
}
bool OnLoadUserArchive(void* instance, std::int32_t index, const void* method) {
    char message[128]{};
    std::snprintf(message, sizeof(message), "recovery_audit archive=enter slot=%d", index);
    LogMessage(message);
    const bool result = g_load_user_archive(instance, index, method);
    std::snprintf(message, sizeof(message), "recovery_audit archive=leave slot=%d result=%s",
                  index, result ? "true" : "false");
    LogMessage(message);
    return result;
}

bool InstallArchiveHook() {
    std::lock_guard<std::mutex> lock(g_setup_mutex);
    if (g_archive_hook_installed) {
        return true;
    }

    Il2CppAttachedThread attached(*g_api);
    if (!attached.attached()) {
        return false;
    }
    void* domain = g_api->domain_get();
    void* assembly = domain == nullptr ? nullptr : g_api->domain_assembly_open(domain, "Core.dll");
    if (assembly == nullptr) {
        assembly = domain == nullptr ? nullptr : g_api->domain_assembly_open(domain, "Il2CppCore.dll");
    }
    void* image = assembly == nullptr ? nullptr : g_api->assembly_get_image(assembly);
    void* datapool = image == nullptr ? nullptr : g_api->class_from_name(image, "", "Datapool");
    if (datapool == nullptr) {
        datapool = image == nullptr ? nullptr : g_api->class_from_name(image, "Il2Cpp", "Datapool");
    }
    void* archive_method = datapool == nullptr ? nullptr :
        g_api->class_get_method_from_name(datapool, "LoadUserArchive", 1);
    void* archive_data_method = datapool == nullptr ? nullptr :
        g_api->class_get_method_from_name(datapool, "LoadUserArchiveData", 0);
    if (archive_method == nullptr && archive_data_method == nullptr) {
        LogMessage("recovery_audit archive=unavailable");
        return false;
    }

    void* original = nullptr;
    if (archive_method != nullptr) {
        if (!g_hooks.Replace(*reinterpret_cast<void**>(archive_method),
                             reinterpret_cast<void*>(OnLoadUserArchive), &original)) {
            LogMessage("recovery_audit archive=hook_failed");
            return false;
        }
        g_load_user_archive = reinterpret_cast<LoadUserArchiveFunction>(original);
    }
    if (archive_data_method != nullptr) {
        if (!g_hooks.Replace(*reinterpret_cast<void**>(archive_data_method),
                             reinterpret_cast<void*>(OnLoadUserArchiveData), &original)) {
            LogMessage("recovery_audit archive_data=hook_failed");
            return false;
        }
        g_load_user_archive_data = reinterpret_cast<LoadUserArchiveDataFunction>(original);
    }
    g_archive_hook_installed = true;
    LogMessage("recovery_audit archive=hook_installed");
    return true;
}

struct OfficialObserverContext {
    void* datapool = nullptr;
    void* load_global_mods = nullptr;
    void* mod_loader_active_mod = nullptr;
    void* mod_loader_run = nullptr;
};

std::optional<OfficialObserverContext> FindOfficialObserverContext(
    const Il2CppRuntime& runtime, void* datapool);

struct OfficialCollectionState {
    bool field_available = false;
    void* collection = nullptr;
};

OfficialCollectionState ReadOfficialCollection(const Il2CppRuntime& runtime,
                                               void* datapool,
                                               std::string_view field_name) {
    const auto datapool_class = runtime.ObjectClass(datapool);
    if (!datapool_class.has_value()) {
        return {};
    }
    const auto offset = runtime.ReferenceInstanceFieldOffset(
        *datapool_class, field_name);
    if (!offset.has_value()) {
        return {};
    }
    return {
        true,
        *reinterpret_cast<void**>(
            reinterpret_cast<std::byte*>(datapool) + *offset),
    };
}

std::string OfficialCollectionCount(const Il2CppRuntime& runtime,
                                    void* datapool,
                                    std::string_view field_name) {
    const OfficialCollectionState collection =
        ReadOfficialCollection(runtime, datapool, field_name);
    if (!collection.field_available) {
        return "unavailable";
    }
    if (collection.collection == nullptr) {
        return "null";
    }
    const auto collection_class = runtime.ObjectClass(collection.collection);
    if (!collection_class.has_value()) {
        return "unavailable";
    }
    std::optional<std::int32_t> count_offset = runtime.FieldOffset(*collection_class, "_size");
    if (!count_offset.has_value()) {
        count_offset = runtime.FieldOffset(*collection_class, "_count");
    }
    if (!count_offset.has_value()) {
        return "unavailable";
    }
    const std::int32_t count = *reinterpret_cast<const std::int32_t*>(
        reinterpret_cast<const std::byte*>(collection.collection) + *count_offset);
    constexpr std::int32_t kMaximumObservedCollectionCount = 1'000'000;
    if (count < 0 || count > kMaximumObservedCollectionCount) {
        return "unavailable";
    }
    return std::to_string(count);
}

std::string OfficialPanelModsCount(void* panel) noexcept {
    if (g_api == nullptr || panel == nullptr) {
        return panel == nullptr ? "null" : "unavailable";
    }
    const std::int32_t mods_offset =
        g_official_panel_mods_offset.load(std::memory_order_acquire);
    if (mods_offset < 0) {
        return "unavailable";
    }
    void* mods = *reinterpret_cast<void**>(
        reinterpret_cast<std::byte*>(panel) + mods_offset);
    if (mods == nullptr) {
        return "null";
    }
    const Il2CppRuntime runtime(*g_api);
    const auto collection_class = runtime.ObjectClass(mods);
    if (!collection_class.has_value()) {
        return "unavailable";
    }
    const auto size_offset = runtime.FieldOffset(*collection_class, "_size");
    if (!size_offset.has_value()) {
        return "unavailable";
    }
    const std::int32_t count = *reinterpret_cast<const std::int32_t*>(
        reinterpret_cast<const std::byte*>(mods) + *size_offset);
    constexpr std::int32_t kMaximumObservedCollectionCount = 1'000'000;
    return count >= 0 && count <= kMaximumObservedCollectionCount
        ? std::to_string(count) : "unavailable";
}

void LogOfficialDatapoolSnapshot(const char* point, void* datapool) noexcept {
    if (g_api == nullptr) {
        return;
    }
    if (datapool == nullptr) {
        datapool = g_official_datapool.load(std::memory_order_acquire);
    }
    if (datapool == nullptr) {
        const std::string message = std::string("official_observer=snapshot point=") + point +
            " datapool=null";
        LogMessage(message.c_str());
        return;
    }
    const Il2CppRuntime runtime(*g_api);
    const std::string user_mods = OfficialCollectionCount(runtime, datapool, "_user_mods");
    const std::string mods = OfficialCollectionCount(runtime, datapool, "mods");
    const std::string active_mods = OfficialCollectionCount(runtime, datapool, "active_mods");
    const std::string activing_mods = OfficialCollectionCount(runtime, datapool, "activing_mods");
    const std::string queue = OfficialCollectionCount(runtime, datapool, "active_mods_queue");
    const std::string message = std::string("official_observer=snapshot point=") + point +
        " user_mods=" + user_mods + " mods=" + mods +
        " active_mods=" + active_mods + " activing_mods=" + activing_mods +
        " queue=" + queue;
    LogMessage(message.c_str());
}

void LogOfficialUiSnapshot(const char* point, void* panel) noexcept {
    if (!g_official_ui_observer_active.load(std::memory_order_acquire)) {
        return;
    }
    void* datapool = g_official_datapool.load(std::memory_order_acquire);
    if (g_api == nullptr || datapool == nullptr) {
        const std::string message = std::string("official_ui_observer=snapshot point=") + point +
            " datapool=" + (datapool == nullptr ? "null" : "unavailable") +
            " panel_mods=" + OfficialPanelModsCount(panel);
        LogMessage(message.c_str());
        return;
    }
    const Il2CppRuntime runtime(*g_api);
    const std::string message = std::string("official_ui_observer=snapshot point=") + point +
        " user_mods=" + OfficialCollectionCount(runtime, datapool, "_user_mods") +
        " mods=" + OfficialCollectionCount(runtime, datapool, "mods") +
        " active_mods=" + OfficialCollectionCount(runtime, datapool, "active_mods") +
        " panel_mods=" + OfficialPanelModsCount(panel);
    LogMessage(message.c_str());
}

void OnOfficialPanelOnEnable(void* panel, const void* method) {
    if (g_official_ui_observer_active.load(std::memory_order_acquire)) {
        LogMessage("official_ui_observer=call target=panel_on_enable phase=enter");
        LogOfficialUiSnapshot("panel_on_enable_enter", panel);
    }
    ModPanelVoidFunction original = g_official_panel_on_enable.load(std::memory_order_acquire);
    if (original == nullptr) {
        std::lock_guard<std::mutex> lock(g_official_ui_observer_publish_mutex);
        original = g_official_panel_on_enable.load(std::memory_order_acquire);
    }
    if (original == nullptr) {
        LogMessage("official_ui_observer=call target=panel_on_enable phase=leave reason=trampoline");
        return;
    }
    original(panel, method);
    if (g_official_ui_observer_active.load(std::memory_order_acquire)) {
        LogOfficialUiSnapshot("panel_on_enable_leave", panel);
        LogMessage("official_ui_observer=call target=panel_on_enable phase=leave");
    }
}

void OnOfficialPanelShowMods(void* panel, const void* method) {
    if (g_official_ui_observer_active.load(std::memory_order_acquire)) {
        LogMessage("official_ui_observer=call target=show_mods phase=enter");
        LogOfficialUiSnapshot("show_mods_enter", panel);
    }
    ModPanelVoidFunction original = g_official_panel_show_mods.load(std::memory_order_acquire);
    if (original == nullptr) {
        std::lock_guard<std::mutex> lock(g_official_ui_observer_publish_mutex);
        original = g_official_panel_show_mods.load(std::memory_order_acquire);
    }
    if (original == nullptr) {
        LogMessage("official_ui_observer=call target=show_mods phase=leave reason=trampoline");
        return;
    }
    original(panel, method);
    if (g_official_ui_observer_active.load(std::memory_order_acquire)) {
        LogOfficialUiSnapshot("show_mods_leave", panel);
        LogMessage("official_ui_observer=call target=show_mods phase=leave");
    }
}

void OnOfficialPanelRefreshMods(void* panel, const void* method) {
    if (g_official_ui_observer_active.load(std::memory_order_acquire)) {
        LogMessage("official_ui_observer=call target=refresh_mods phase=enter");
        LogOfficialUiSnapshot("refresh_mods_enter", panel);
    }
    ModPanelVoidFunction original = g_official_panel_refresh_mods.load(std::memory_order_acquire);
    if (original == nullptr) {
        std::lock_guard<std::mutex> lock(g_official_ui_observer_publish_mutex);
        original = g_official_panel_refresh_mods.load(std::memory_order_acquire);
    }
    if (original == nullptr) {
        LogMessage("official_ui_observer=call target=refresh_mods phase=leave reason=trampoline");
        return;
    }
    original(panel, method);
    if (g_official_ui_observer_active.load(std::memory_order_acquire)) {
        LogOfficialUiSnapshot("refresh_mods_leave", panel);
        LogMessage("official_ui_observer=call target=refresh_mods phase=leave");
    }
}

void OnOfficialItemSetup(void* item, void* node, void* panel, const void* method) {
    if (g_official_ui_observer_active.load(std::memory_order_acquire)) {
        const std::string message = std::string("official_ui_observer=call target=item_setup phase=enter item=") +
            (item == nullptr ? "null" : "set") + " node=" +
            (node == nullptr ? "null" : "set") + " parent=" +
            (panel == nullptr ? "null" : "set");
        LogMessage(message.c_str());
        LogOfficialUiSnapshot("item_setup_enter", panel);
    }
    ModItemSetupFunction original = g_official_item_setup.load(std::memory_order_acquire);
    if (original == nullptr) {
        std::lock_guard<std::mutex> lock(g_official_ui_observer_publish_mutex);
        original = g_official_item_setup.load(std::memory_order_acquire);
    }
    if (original == nullptr) {
        LogMessage("official_ui_observer=call target=item_setup phase=leave reason=trampoline");
        return;
    }
    original(item, node, panel, method);
    if (g_official_ui_observer_active.load(std::memory_order_acquire)) {
        LogOfficialUiSnapshot("item_setup_leave", panel);
        LogMessage("official_ui_observer=call target=item_setup phase=leave");
    }
}

bool PrepareOfficialUiObserver(const Il2CppRuntime& runtime) {
    {
        std::lock_guard<std::mutex> lock(g_official_ui_observer_mutex);
        if (g_official_ui_observer_state == OfficialUiObserverState::kReady) return true;
        if (g_official_ui_observer_state != OfficialUiObserverState::kUnprepared) return false;
        g_official_ui_observer_state = OfficialUiObserverState::kInstalling;
    }
    const auto fail = [](const char* reason) {
        g_official_ui_observer_active.store(false, std::memory_order_release);
        std::lock_guard<std::mutex> lock(g_official_ui_observer_mutex);
        g_official_ui_observer_state = OfficialUiObserverState::kRejected;
        const std::string message = std::string("official_ui_observer=unavailable reason=") + reason;
        LogMessage(message.c_str());
    };
    const auto game = runtime.FindImage({"Il2CppGame.dll", "Game.dll"});
    if (!game.has_value()) { fail("game_image"); return false; }
    const auto panel_class = runtime.FindClass(*game, {"", "Il2Cpp"}, "ModPanelController");
    const auto item_class = runtime.FindClass(*game, {"", "Il2Cpp"}, "ModItemController");
    if (!panel_class.has_value() || !item_class.has_value()) { fail("class"); return false; }
    const auto on_enable = runtime.FindMethod(*panel_class, "OnEnable", 0);
    const auto show_mods = runtime.FindMethod(*panel_class, "ShowMods", 0);
    const auto refresh_mods = runtime.FindMethod(*panel_class, "RefreshMods", 0);
    const auto item_setup = runtime.FindMethodByParameterTypes(
        *item_class, "Setup",
        {kOfficialUiItemSetupNodeType, kOfficialUiItemSetupPanelType});
    const auto mods_offset = runtime.ReferenceInstanceFieldOffset(
        *panel_class, kOfficialUiPanelModsField);
    const OfficialUiObserverMembers members{
        on_enable.has_value(),
        show_mods.has_value(),
        refresh_mods.has_value(),
        item_setup.has_value(),
        mods_offset.has_value(),
    };
    if (!OfficialUiObserverMembersReady(members)) {
        LogOfficialUiMetadataCandidates(runtime, *item_class, *panel_class, members);
        const std::string message =
            std::string("member missing=") + OfficialUiObserverMissingMembers(members);
        fail(message.c_str());
        return false;
    }
    const auto on_enable_code = runtime.MethodCode(*on_enable);
    const auto show_mods_code = runtime.MethodCode(*show_mods);
    const auto refresh_mods_code = runtime.MethodCode(*refresh_mods);
    const auto item_setup_code = runtime.MethodCode(*item_setup);
    const GameProfile& profile = SupportedGameProfile();
    const auto base = reinterpret_cast<std::uintptr_t>(g_api->image_base);
    const OfficialUiObserverValidation validation = ValidateOfficialUiObserverTargets(
        profile, base,
        on_enable_code ? reinterpret_cast<std::uintptr_t>(*on_enable_code) : 0,
        show_mods_code ? reinterpret_cast<std::uintptr_t>(*show_mods_code) : 0,
        refresh_mods_code ? reinterpret_cast<std::uintptr_t>(*refresh_mods_code) : 0,
        item_setup_code ? reinterpret_cast<std::uintptr_t>(*item_setup_code) : 0,
        MatchesFingerprint(profile.ui_observer.panel_on_enable),
        MatchesFingerprint(profile.ui_observer.panel_show_mods),
        MatchesFingerprint(profile.ui_observer.panel_refresh_mods),
        MatchesFingerprint(profile.ui_observer.item_setup));
    if (validation != OfficialUiObserverValidation::kValid) {
        fail(OfficialUiObserverValidationReason(validation)); return false;
    }
    void* on_enable_original = nullptr;
    void* show_mods_original = nullptr;
    void* refresh_mods_original = nullptr;
    void* item_setup_original = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_official_ui_observer_publish_mutex);
        if (!g_official_ui_observer_hooks.Replace(*on_enable_code,
                reinterpret_cast<void*>(OnOfficialPanelOnEnable), &on_enable_original) ||
            on_enable_original == nullptr) { fail("hook_install"); return false; }
        g_official_panel_on_enable.store(reinterpret_cast<ModPanelVoidFunction>(on_enable_original),
            std::memory_order_release);
        if (!g_official_ui_observer_hooks.Replace(*show_mods_code,
                reinterpret_cast<void*>(OnOfficialPanelShowMods), &show_mods_original) ||
            show_mods_original == nullptr) { fail("hook_install"); return false; }
        g_official_panel_show_mods.store(reinterpret_cast<ModPanelVoidFunction>(show_mods_original),
            std::memory_order_release);
        if (!g_official_ui_observer_hooks.Replace(*refresh_mods_code,
                reinterpret_cast<void*>(OnOfficialPanelRefreshMods), &refresh_mods_original) ||
            refresh_mods_original == nullptr) { fail("hook_install"); return false; }
        g_official_panel_refresh_mods.store(reinterpret_cast<ModPanelVoidFunction>(refresh_mods_original),
            std::memory_order_release);
        if (!g_official_ui_observer_hooks.Replace(*item_setup_code,
                reinterpret_cast<void*>(OnOfficialItemSetup), &item_setup_original) ||
            item_setup_original == nullptr) { fail("hook_install"); return false; }
        g_official_item_setup.store(reinterpret_cast<ModItemSetupFunction>(item_setup_original),
            std::memory_order_release);
    }
    g_official_panel_mods_offset.store(*mods_offset, std::memory_order_release);
    g_official_ui_observer_active.store(true, std::memory_order_release);
    {
        std::lock_guard<std::mutex> lock(g_official_ui_observer_mutex);
        g_official_ui_observer_state = OfficialUiObserverState::kReady;
    }
    LogMessage("official_ui_observer=ready");
    return true;
}

void* OnOfficialLoadGlobalMods(void* instance, const void* method) {
    LogMessage("official_observer=call target=load_global_mods phase=enter");
    LogOfficialDatapoolSnapshot("load_global_mods_enter", instance);
    LoadGlobalModsFunction original =
        g_official_load_global_mods.load(std::memory_order_acquire);
    if (original == nullptr) {
        std::lock_guard<std::mutex> publish_lock(g_official_observer_publish_mutex);
        original = g_official_load_global_mods.load(std::memory_order_acquire);
    }
    if (original == nullptr) {
        LogMessage("official_observer=call target=load_global_mods phase=leave iterator=null reason=trampoline");
        return nullptr;
    }
    void* iterator = original(instance, method);
    LogOfficialDatapoolSnapshot("load_global_mods_leave", instance);
    const std::string message = std::string(
        "official_observer=call target=load_global_mods phase=leave iterator=") +
        (iterator == nullptr ? "null" : "set");
    LogMessage(message.c_str());
    return iterator;
}

void OnOfficialModLoaderActiveMod(void* instance, void* callback, void* node,
                                  void* promise, const void* method) {
    const std::string entered = std::string(
        "official_observer=call target=mod_loader_active_mod phase=enter callback=") +
        (callback == nullptr ? "null" : "set") + " node=" +
        (node == nullptr ? "null" : "set") + " promise=" +
        (promise == nullptr ? "null" : "set");
    LogMessage(entered.c_str());
    LogOfficialDatapoolSnapshot("mod_loader_active_mod_enter");
    ModLoaderActiveModFunction original =
        g_official_mod_loader_active_mod.load(std::memory_order_acquire);
    if (original == nullptr) {
        std::lock_guard<std::mutex> publish_lock(g_official_observer_publish_mutex);
        original = g_official_mod_loader_active_mod.load(std::memory_order_acquire);
    }
    if (original == nullptr) {
        LogMessage("official_observer=call target=mod_loader_active_mod phase=leave reason=trampoline");
        return;
    }
    original(instance, callback, node, promise, method);
    LogOfficialDatapoolSnapshot("mod_loader_active_mod_leave");
    LogMessage("official_observer=call target=mod_loader_active_mod phase=leave");
}

void OnOfficialModLoaderRun(void* instance, void* enumerator, const void* method) {
    const std::string entered = std::string(
        "official_observer=call target=mod_loader_run phase=enter enumerator=") +
        (enumerator == nullptr ? "null" : "set");
    LogMessage(entered.c_str());
    LogOfficialDatapoolSnapshot("mod_loader_run_enter");
    ModLoaderRunFunction original =
        g_official_mod_loader_run.load(std::memory_order_acquire);
    if (original == nullptr) {
        std::lock_guard<std::mutex> publish_lock(g_official_observer_publish_mutex);
        original = g_official_mod_loader_run.load(std::memory_order_acquire);
    }
    if (original == nullptr) {
        LogMessage("official_observer=call target=mod_loader_run phase=leave reason=trampoline");
        return;
    }
    original(instance, enumerator, method);
    LogOfficialDatapoolSnapshot("mod_loader_run_leave");
    LogMessage("official_observer=call target=mod_loader_run phase=leave");
}

bool InstallOfficialActivationObserver(const Il2CppRuntime& runtime,
                                       const OfficialObserverContext& context) {
    std::lock_guard<std::mutex> lock(g_official_observer_mutex);
    if (g_official_observer_state == OfficialObserverState::kReady) {
        return true;
    }
    if (g_official_observer_state == OfficialObserverState::kRejected) {
        return false;
    }
    if (g_official_observer_state == OfficialObserverState::kUnprepared) {
        g_official_observer_state = OfficialObserverState::kInstalling;
    }
    if (g_official_observer_state != OfficialObserverState::kInstalling) {
        return false;
    }

    const auto fail = [](const char* reason) {
        // Once Dobby has published a trampoline, retain that transparent
        // forwarding hook on an observer rejection. Destroying a live
        // trampoline can strand a wrapper that already entered the target.
        // The rejected state prevents any canary invocation or retry.
        g_official_observer_active.store(false, std::memory_order_release);
        g_official_observer_state = OfficialObserverState::kRejected;
        const std::string message = std::string(
            "official_observer=unavailable reason=") + reason;
        LogMessage(message.c_str());
    };

    const auto load_global_code = runtime.MethodCode(context.load_global_mods);
    const auto mod_loader_active_code = runtime.MethodCode(
        context.mod_loader_active_mod);
    const auto run_code = runtime.MethodCode(context.mod_loader_run);
    const GameProfile& profile = SupportedGameProfile();
    const auto base = reinterpret_cast<std::uintptr_t>(g_api->image_base);
    const OfficialObserverValidation validation =
        ValidateOfficialObserverTargets(
            profile,
            base,
            load_global_code.has_value()
                ? reinterpret_cast<std::uintptr_t>(*load_global_code)
                : 0,
            mod_loader_active_code.has_value()
                ? reinterpret_cast<std::uintptr_t>(*mod_loader_active_code)
                : 0,
            run_code.has_value()
                ? reinterpret_cast<std::uintptr_t>(*run_code)
                : 0,
            MatchesFingerprint(profile.mod_loader_run));
    if (validation != OfficialObserverValidation::kValid) {
        fail(OfficialObserverValidationReason(validation));
        return false;
    }


    void* load_global_original = nullptr;
    void* mod_loader_active_original = nullptr;
    void* run_original = nullptr;
    {
        // Dobby activates each target before Replace returns. Wrappers that
        // race installation wait on this mutex until their trampoline is
        // published; a rejected partial installation remains transparent and
        // is never used to start the canary.
        std::lock_guard<std::mutex> publish_lock(g_official_observer_publish_mutex);
        if (!g_official_observer_hooks.Replace(
                *load_global_code, reinterpret_cast<void*>(OnOfficialLoadGlobalMods),
                &load_global_original) || load_global_original == nullptr) {
            fail("hook_install");
            return false;
        }
        g_official_load_global_mods.store(
            reinterpret_cast<LoadGlobalModsFunction>(load_global_original),
            std::memory_order_release);

        if (!g_official_observer_hooks.Replace(
                *mod_loader_active_code,
                reinterpret_cast<void*>(OnOfficialModLoaderActiveMod),
                &mod_loader_active_original) ||
            mod_loader_active_original == nullptr) {
            fail("hook_install");
            return false;
        }
        g_official_mod_loader_active_mod.store(
            reinterpret_cast<ModLoaderActiveModFunction>(
                mod_loader_active_original),
            std::memory_order_release);

        if (!g_official_observer_hooks.Replace(
                *run_code, reinterpret_cast<void*>(OnOfficialModLoaderRun),
                &run_original) || run_original == nullptr) {
            fail("hook_install");
            return false;
        }
        g_official_mod_loader_run.store(
            reinterpret_cast<ModLoaderRunFunction>(run_original),
            std::memory_order_release);
    }
    g_official_datapool.store(context.datapool, std::memory_order_release);
    g_official_observer_active.store(true, std::memory_order_release);
    g_official_observer_state = OfficialObserverState::kReady;
    LogMessage("official_observer=ready");
    LogOfficialDatapoolSnapshot("observer_ready", context.datapool);
    return true;
}

bool InstallOfficialCompatibilityHooks() {
    bool expected = false;
    if (!g_official_compatibility_installed.compare_exchange_strong(
            expected, true, std::memory_order_acq_rel)) {
        return true;
    }
    if (g_api == nullptr) {
        return false;
    }
    const std::string mod_root = GetModRoot();
    const OfficialResourceUriStats uri = InstallOfficialResourceUriHooks(
        *g_api, mod_root, &g_official_compatibility_hooks);
    const TmpGlyphHookStats glyph = InstallTmpGlyphHook(
        *g_api, &g_official_compatibility_hooks);
    const UiRevealStats ui = InstallOfficialUiRevealHook(
        *g_api, &g_official_ui_hooks);
    char message[256]{};
    std::snprintf(message, sizeof(message),
                  "official_compatibility uri_sprite=%s uri_audio=%s uri_texture=%s "
                  "tmp_glyph=%s ui_reveal=%s activation=manual_ui_only",
                  uri.sprite_ready ? "ready" : "unavailable",
                  uri.audio_ready ? "ready" : "unavailable",
                  uri.texture_ready ? "ready" : "unavailable",
                  glyph.ready ? "ready" : "unavailable",
                  ui.ready ? "ready" : "unavailable");
    LogMessage(message);
    return uri.sprite_ready && uri.audio_ready && uri.texture_ready && glyph.ready && ui.ready;
}

bool PrepareOfficialActivationObserver(const Il2CppRuntime& runtime,
                                       void* datapool) {
    {
        std::lock_guard<std::mutex> lock(g_official_observer_mutex);
        if (g_official_observer_state == OfficialObserverState::kReady) {
            return true;
        }
        if (g_official_observer_state == OfficialObserverState::kRejected ||
            g_official_observer_state == OfficialObserverState::kInstalling) {
            return false;
        }
        g_official_observer_state = OfficialObserverState::kInstalling;
    }
    if (datapool == nullptr) {
        LogMessage("official_observer=unavailable reason=datapool_instance");
        std::lock_guard<std::mutex> lock(g_official_observer_mutex);
        g_official_observer_state = OfficialObserverState::kRejected;
        return false;
    }
    const auto context = FindOfficialObserverContext(runtime, datapool);
    if (!context.has_value()) {
        std::lock_guard<std::mutex> lock(g_official_observer_mutex);
        g_official_observer_state = OfficialObserverState::kRejected;
        return false;
    }
    return InstallOfficialActivationObserver(runtime, *context);
}

std::optional<OfficialObserverContext> FindOfficialObserverContext(
    const Il2CppRuntime& runtime, void* datapool) {
    const auto core = runtime.FindImage({"Core.dll", "Il2CppCore.dll"});
    if (!core.has_value()) {
        LogMessage("official_observer=unavailable reason=core_image");
        return std::nullopt;
    }
    const auto datapool_class = runtime.FindClass(
        *core, {"", "Il2Cpp"}, "Datapool");
    const auto mod_loader_class = runtime.FindClass(
        *core, {"", "Il2Cpp"}, "ModLoader");
    if (!datapool_class.has_value()) {
        LogMessage("official_observer=unavailable reason=datapool_class");
        return std::nullopt;
    }
    if (!mod_loader_class.has_value()) {
        LogMessage("official_observer=unavailable reason=mod_loader_class");
        return std::nullopt;
    }
    const auto load_global_mods = runtime.FindMethod(
        *datapool_class, "LoadGlobalMods", 0);
    const auto mod_loader_active_mod = runtime.FindMethod(
        *mod_loader_class, "ActiveMod", 3);
    const auto mod_loader_run = runtime.FindMethodByFirstParameter(
        *mod_loader_class, "Run", 1, "System.Collections.IEnumerator");
    if (!load_global_mods.has_value()) {
        LogMessage("official_observer=unavailable reason=load_global_mods_method");
        return std::nullopt;
    }
    if (!mod_loader_active_mod.has_value()) {
        LogMessage("official_observer=unavailable reason=mod_loader_active_mod_method");
        return std::nullopt;
    }
    if (!mod_loader_run.has_value()) {
        LogMessage("official_observer=unavailable reason=mod_loader_run_method");
        return std::nullopt;
    }
    return OfficialObserverContext{
        datapool,
        *load_global_mods,
        *mod_loader_active_mod,
        *mod_loader_run,
    };
}
bool MatchesFingerprint(const CodeFingerprint& fingerprint) {
    if (g_api == nullptr || g_api->image_base == nullptr ||
        fingerprint.rva > g_api->image_size ||
        fingerprint.bytes.size() > g_api->image_size - fingerprint.rva) {
        return false;
    }
    const auto* address = reinterpret_cast<const std::uint8_t*>(g_api->image_base) +
        fingerprint.rva;
    return std::equal(
        fingerprint.bytes.begin(), fingerprint.bytes.end(), address);
}

void* OnLoadConfig(void* instance, int mode, const void* method) {
    LoadConfigFunction load_config = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_config_hook_mutex);
        load_config = g_load_config;
    }
    LogMessage("lifecycle=load_config_observed");
    if (load_config == nullptr) {
        if (g_runtime != nullptr) {
            g_runtime->Fail(FailureCode::kHookInstallFailed);
        }
        return nullptr;
    }
    if (g_backend_route.route() == BackendRoute::kOfficialCanary) {
        bool observer_ready = false;
        bool compatibility_ready = false;
        bool ui_observer_ready = false;
        try {
            if (g_api != nullptr) {
                Il2CppRuntime runtime(*g_api);
                observer_ready = PrepareOfficialActivationObserver(runtime, instance);
                compatibility_ready = InstallOfficialCompatibilityHooks();
                ui_observer_ready = PrepareOfficialUiObserver(runtime);
            }
        } catch (...) {
            LogMessage("official_compatibility unavailable reason=native_exception");
        }
        if (!ui_observer_ready) {
            LogMessage("official_ui_observer=unavailable reason=prepare");
        }
        if (!observer_ready || !compatibility_ready) {
            g_backend_route.MarkFailed(BackendRoute::kOfficialCanary);
            if (g_runtime != nullptr) {
                g_runtime->Fail(FailureCode::kOfficialPreflightFailed);
            }
            LogMessage("official_canary=failed phase=preflight reason=compatibility");
        }
        return load_config(instance, mode, method);
    }
    InstallPostProcessHooks();
    InstallArchiveHook();
    InstallRecoveryHook();
    return load_config(instance, mode, method);
}

}  // namespace

bool InstallModHooks(const Il2CppApi& api, RuntimeController& runtime) {
    const GameProfile& profile = SupportedGameProfile();
    const std::uintptr_t base = reinterpret_cast<std::uintptr_t>(api.image_base);
    if (api.image_size == 0 ||
        !MatchesGameProfile(profile, base, base, base + api.image_size)) {
        runtime.MarkUnsupported();
        LogState(runtime.state());
        LogFailure(runtime.failure());
        return true;
    }
    LogMessage("profile=accepted");

    const BackendRoute route = CompiledBackendRoute();
    if (!g_backend_route.Claim(route)) {
        runtime.Fail(FailureCode::kHookInstallFailed);
        return false;
    }
    char route_message[128]{};
    std::snprintf(route_message, sizeof(route_message),
                  "backend=%s phase=preflight", BackendRouteName(route));
    LogMessage(route_message);

    g_api = &api;
    g_runtime = &runtime;
    if (route == BackendRoute::kOfficialCanary) {
        // Install only the RVA-gated lifecycle hook on the resolver worker.
        // All IL2CPP metadata access is deferred to the natural UnityMain
        // LoadConfig callback, after the managed domain is fully initialized.
        std::lock_guard<std::mutex> config_hook_lock(g_config_hook_mutex);
        void* config_original = nullptr;
        if (!g_hooks.Replace(
                reinterpret_cast<void*>(TargetAddress(
                    profile, HookTarget::kLoadConfig, base)),
                reinterpret_cast<void*>(OnLoadConfig), &config_original) ||
            config_original == nullptr) {
            g_hooks.Rollback();
            g_backend_route.MarkFailed(route);
            runtime.Fail(FailureCode::kHookInstallFailed);
            return false;
        }
        g_load_config = reinterpret_cast<LoadConfigFunction>(config_original);
    } else {
        g_lifecycle = std::make_unique<LifecycleGate>(
            Schedule, [&runtime]() { OnInjectionWindow(&runtime); });
        std::lock_guard<std::mutex> config_hook_lock(g_config_hook_mutex);
        void* config_original = nullptr;
        void* refresh_original = nullptr;
        void* user_original = nullptr;
        void* global_original = nullptr;
        if (!g_hooks.Replace(
                reinterpret_cast<void*>(TargetAddress(
                    profile, HookTarget::kRefreshMods, base)),
                reinterpret_cast<void*>(StubPointer), &refresh_original) ||
            !g_hooks.Replace(
                reinterpret_cast<void*>(TargetAddress(
                    profile, HookTarget::kLoadUserMods, base)),
                reinterpret_cast<void*>(StubVoid), &user_original) ||
            !g_hooks.Replace(
                reinterpret_cast<void*>(TargetAddress(
                    profile, HookTarget::kLoadGlobalMods, base)),
                reinterpret_cast<void*>(StubPointer), &global_original) ||
            !g_hooks.Replace(
                reinterpret_cast<void*>(TargetAddress(
                    profile, HookTarget::kLoadConfig, base)),
                reinterpret_cast<void*>(OnLoadConfig), &config_original) ||
            refresh_original == nullptr || user_original == nullptr ||
            global_original == nullptr || config_original == nullptr) {
            g_hooks.Rollback();
            g_lifecycle.reset();
            g_backend_route.MarkFailed(route);
            runtime.Fail(FailureCode::kHookInstallFailed);
            return false;
        }
        g_load_config = reinterpret_cast<LoadConfigFunction>(config_original);
    }
    if (route == BackendRoute::kStagedNative &&
        !g_backend_route.MarkStarted(route)) {
        g_hooks.Rollback();
        g_lifecycle.reset();
        runtime.Fail(FailureCode::kHookInstallFailed);
        return false;
    }

    std::snprintf(route_message, sizeof(route_message),
                  "hooks=installed backend=%s", BackendRouteName(route));
    LogMessage(route_message);
    return true;
}

}  // namespace modloader
