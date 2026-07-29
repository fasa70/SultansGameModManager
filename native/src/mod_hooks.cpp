#include "modloader/mod_hooks.h"

#include "modloader/android_log.h"
#include "modloader/bootstrap_notify.h"
#include "modloader/game_profile.h"
#include "modloader/hook_engine.h"
#include "modloader/il2cpp_runtime.h"
#include "modloader/mod_lifecycle.h"
#include "modloader/mod_root.h"
#include "modloader/native_mod_loader.h"
#include "modloader/resource_overrides.h"
#include "modloader/resource_hooks.h"
#include "modloader/mod_file_index.h"

#include <algorithm>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

namespace modloader {
namespace {

using LoadConfigFunction = void (*)(void*, int, const void*);
using LoadSingleFileFunction = void (*)(void*, void*, void*, void*, const void*);
using PostProcessFunction = void (*)(void*, const void*);
using LoadUserArchiveFunction = bool (*)(void*, std::int32_t, const void*);
using LoadUserArchiveDataFunction = void (*)(void*, const void*);
using RiteRenderInitFunction = void (*)(void*, void*, const void*);

HookEngine g_hooks;
std::unique_ptr<LifecycleGate> g_lifecycle;
std::mutex g_setup_mutex;
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

void* StubPointer(void*) {
    return nullptr;
}

int StubInteger(void*) {
    return 0;
}

void* StubModLoaderRun() {
    return nullptr;
}

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

void OnLoadConfig(void* instance, int mode, const void* method) {
    LogMessage("lifecycle=load_config_observed");
    InstallPostProcessHooks();
    InstallArchiveHook();
    InstallRecoveryHook();
    g_load_config(instance, mode, method);
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

    g_api = &api;
    g_runtime = &runtime;
    g_lifecycle = std::make_unique<LifecycleGate>(
        Schedule, [&runtime]() { OnInjectionWindow(&runtime); });

    void* original = nullptr;
    if (!g_hooks.Replace(reinterpret_cast<void*>(TargetAddress(profile, HookTarget::kRefreshMods, base)),
                         reinterpret_cast<void*>(StubPointer), &original) ||
        !g_hooks.Replace(reinterpret_cast<void*>(TargetAddress(profile, HookTarget::kLoadUserMods, base)),
                         reinterpret_cast<void*>(StubInteger), &original) ||
        !g_hooks.Replace(reinterpret_cast<void*>(TargetAddress(profile, HookTarget::kLoadGlobalMods, base)),
                         reinterpret_cast<void*>(StubInteger), &original) ||
        !g_hooks.Replace(reinterpret_cast<void*>(TargetAddress(profile, HookTarget::kModLoaderRun, base)),
                         reinterpret_cast<void*>(StubModLoaderRun), &original) ||
        !g_hooks.Replace(reinterpret_cast<void*>(TargetAddress(profile, HookTarget::kLoadConfig, base)),
                         reinterpret_cast<void*>(OnLoadConfig), &original)) {
        g_hooks.Rollback();
        g_lifecycle.reset();
        runtime.Fail(FailureCode::kHookInstallFailed);
        return false;
    }
    g_load_config = reinterpret_cast<LoadConfigFunction>(original);

    LogMessage("hooks=stub_and_load_config_installed");
    return true;
}

}  // namespace modloader
