#include "modloader/backend_route.h"
#include "modloader/canonical_dictionary.h"
#include "modloader/config_catalog.h"
#include "modloader/game_profile.h"
#include "modloader/mod_file_index.h"
#include "modloader/mod_lifecycle.h"
#include "modloader/mod_path.h"
#include "modloader/official_observer_validation.h"
#include "modloader/official_canary.h"
#include "modloader/resource_overrides.h"
#include "modloader/resource_uri.h"
#include "modloader/runtime_state.h"

#include <atomic>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <initializer_list>
#include <iostream>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

namespace {

using modloader::FailureCode;
using modloader::ResolveStatus;
using modloader::RuntimeController;
using modloader::RuntimeState;

int failures = 0;

void Check(bool condition, const char* message) {
    if (!condition) {
        std::cerr << "FAIL: " << message << '\n';
        ++failures;
    }
}

class SequenceResolver final : public modloader::Il2CppResolver {
  public:
    SequenceResolver(std::initializer_list<ResolveStatus> statuses)
        : statuses_(statuses) {}

    ResolveStatus TryResolve() override {
        const std::size_t index = calls_.fetch_add(1);
        if (statuses_.empty()) {
            return ResolveStatus::kNotLoaded;
        }
        if (index >= statuses_.size()) {
            return statuses_.back();
        }
        return statuses_[index];
    }

    std::size_t calls() const {
        return calls_.load();
    }

  private:
    std::vector<ResolveStatus> statuses_;
    std::atomic<std::size_t> calls_{0};
};

void TestBackendRoute() {
    modloader::BackendRouteController route;
    Check(route.route() == modloader::BackendRoute::kUnselected,
          "backend route must start unselected");
    Check(route.Claim(modloader::BackendRoute::kOfficialCanary),
          "official route must claim an unselected process");
    Check(!route.Claim(modloader::BackendRoute::kStagedNative),
          "a second backend must not claim the process");
    Check(route.MarkStarted(modloader::BackendRoute::kOfficialCanary),
          "claimed official route must start");
    Check(route.MarkFailed(modloader::BackendRoute::kOfficialCanary),
          "started official route must enter terminal failure");
    Check(!route.MarkReady(modloader::BackendRoute::kOfficialCanary),
          "failed official route must not become ready");
    Check(!route.MarkStarted(modloader::BackendRoute::kStagedNative),
          "failed official route must not hot-switch to staged native");
}

void TestReadyBackendRouteIsTerminal() {
    modloader::BackendRouteController route;
    Check(route.Claim(modloader::BackendRoute::kStagedNative),
          "staged route must claim an unselected process");
    Check(route.MarkStarted(modloader::BackendRoute::kStagedNative),
          "claimed staged route must start");
    Check(route.MarkReady(modloader::BackendRoute::kStagedNative),
          "started staged route must become ready");
    Check(!route.MarkFailed(modloader::BackendRoute::kStagedNative),
          "ready staged route must remain terminal");
}

void TestOfficialCanaryCompletion() {
    modloader::OfficialCanaryCompletion completion;
    const void* promise = reinterpret_cast<void*>(0x1000);
    Check(completion.BeginRefreshCall(), "official refresh call must start once");
    Check(!completion.BeginRefreshCall(), "official refresh call must be exactly once");
    Check(completion.TrackPromise(promise) ==
              modloader::OfficialCanaryDecision::kPending,
          "tracked promise must remain pending before completion");
    Check(completion.ObservePromise(
              reinterpret_cast<void*>(0x2000),
              modloader::OfficialPromiseCompletion::kResolved) ==
              modloader::OfficialCanaryDecision::kUnchanged,
          "unrelated promise completion must be ignored");
    Check(completion.ObservePromise(
              promise, modloader::OfficialPromiseCompletion::kResolved) ==
              modloader::OfficialCanaryDecision::kPending,
          "promise resolution alone must wait for LoadConfig iterator");
    Check(completion.ObserveLoadConfig(true) ==
              modloader::OfficialCanaryDecision::kReady,
          "promise resolution and iterator return must make official route ready");
    Check(completion.ObservePromise(
              promise, modloader::OfficialPromiseCompletion::kRejected) ==
              modloader::OfficialCanaryDecision::kReady,
          "ready official completion must remain terminal");
}

void TestOfficialCanaryPromiseState() {
    const void* promise = reinterpret_cast<void*>(0x3000);

    modloader::OfficialCanaryCompletion resolved;
    Check(resolved.BeginRefreshCall(), "resolved state test must start");
    Check(resolved.ObservePromise(
              promise, modloader::OfficialPromiseCompletion::kResolved) ==
              modloader::OfficialCanaryDecision::kUnchanged,
          "completion before promise identity is known must be ignored");
    Check(resolved.TrackPromise(promise) ==
              modloader::OfficialCanaryDecision::kPending,
          "resolved state test must track promise");
    Check(resolved.ObservePromiseState(
              promise, modloader::OfficialPromiseState::kResolved) ==
              modloader::OfficialCanaryDecision::kPending,
          "resolved promise state must still wait for LoadConfig iterator");
    Check(resolved.ObserveLoadConfig(true) ==
              modloader::OfficialCanaryDecision::kReady,
          "resolved state and iterator return must make route ready");

    modloader::OfficialCanaryCompletion rejected;
    Check(rejected.BeginRefreshCall(), "rejected state test must start");
    Check(rejected.TrackPromise(promise) ==
              modloader::OfficialCanaryDecision::kPending,
          "rejected state test must track promise");
    Check(rejected.ObservePromiseState(
              promise, modloader::OfficialPromiseState::kRejected) ==
              modloader::OfficialCanaryDecision::kFailed,
          "rejected promise state must fail the route");

    modloader::OfficialCanaryCompletion pending;
    Check(pending.BeginRefreshCall(), "pending state test must start");
    Check(pending.TrackPromise(promise) ==
              modloader::OfficialCanaryDecision::kPending,
          "pending state test must track promise");
    Check(pending.ObservePromiseState(
              promise, modloader::OfficialPromiseState::kPending) ==
              modloader::OfficialCanaryDecision::kPending,
          "pending promise state must not complete the route");
    Check(pending.ObserveLoadConfig(false) ==
              modloader::OfficialCanaryDecision::kFailed,
          "null LoadConfig iterator must fail closed");
}

void TestModPath() {
    Check(!modloader::BuildModRoot("").has_value(), "empty path must fail");
    Check(!modloader::BuildModRoot("   ").has_value(), "blank path must fail");
    Check(modloader::BuildModRoot("/x/files") == "/x/files/Mod",
          "path must append Mod");
    Check(modloader::BuildModRoot("/x/files/") == "/x/files/Mod",
          "path must remove trailing slash");
    Check(modloader::BuildModRoot("/x/files////") == "/x/files/Mod",
          "path must remove repeated trailing slashes");
}

void TestSingleStarter() {
    RuntimeController runtime;
    std::atomic<int> winners{0};
    std::vector<std::thread> threads;
    for (int index = 0; index < 16; ++index) {
        threads.emplace_back([&]() {
            if (runtime.TryBeginWaiting()) {
                winners.fetch_add(1);
            }
        });
    }
    for (auto& thread : threads) {
        thread.join();
    }

    Check(winners.load() == 1, "only one bootstrap caller may start worker");
    Check(runtime.state() == RuntimeState::kWaitingForIl2Cpp,
          "winner must enter waiting state");
    Check(!runtime.TryBeginWaiting(), "bootstrap must be idempotent");
}

void TestReadyTerminalState() {
    RuntimeController runtime;
    Check(runtime.TryBeginWaiting(), "ready test must start");
    Check(runtime.MarkInitializing(), "waiting must transition to initializing");
    Check(runtime.MarkReady(), "initializing must transition to ready");
    Check(!runtime.Fail(FailureCode::kUnexpectedNativeException),
          "ready state must reject failure transition");
    Check(runtime.state() == RuntimeState::kReady, "ready state must remain terminal");
    Check(runtime.failure() == FailureCode::kNone, "ready state must not have failure");
}

void TestUnsupportedTerminalState() {
    RuntimeController runtime;
    Check(runtime.TryBeginWaiting(), "unsupported test must start");
    Check(runtime.MarkInitializing(), "unsupported test must initialize");
    Check(runtime.MarkUnsupported(), "initializing must transition to unsupported");
    Check(runtime.state() == RuntimeState::kUnsupported, "unsupported state must publish");
    Check(runtime.failure() == FailureCode::kUnsupportedGameVersion,
          "unsupported state must explain the fail-open decision");
    Check(!runtime.Fail(FailureCode::kHookInstallFailed),
          "unsupported state must reject later failure transition");
}

void TestFailureSnapshotConsistency() {
    for (int iteration = 0; iteration < 1000; ++iteration) {
        RuntimeController runtime;
        std::atomic<bool> stop{false};
        std::atomic<bool> inconsistent{false};
        std::thread reader([&]() {
            while (!stop.load(std::memory_order_acquire)) {
                if (runtime.state() == RuntimeState::kFailed &&
                    runtime.failure() == FailureCode::kNone) {
                    inconsistent.store(true, std::memory_order_release);
                }
            }
        });

        runtime.Fail(FailureCode::kUnexpectedNativeException);
        stop.store(true, std::memory_order_release);
        reader.join();
        Check(!inconsistent.load(std::memory_order_acquire),
              "failed state must publish a non-none failure atomically");
    }
}

void TestResolverSuccess() {
    RuntimeController runtime;
    SequenceResolver resolver{
        ResolveStatus::kNotLoaded,
        ResolveStatus::kDomainUnavailable,
        ResolveStatus::kReady,
    };
    Check(runtime.TryBeginWaiting(), "resolver success test must start");
    std::size_t waits = 0;
    modloader::RunResolverLoop(
        runtime,
        resolver,
        5,
        std::chrono::milliseconds(1),
        [&](std::chrono::milliseconds) { ++waits; });

    Check(runtime.state() == RuntimeState::kInitializing,
          "resolver must stop at hook initialization");
    Check(resolver.calls() == 3, "resolver must stop after success");
    Check(waits == 2, "resolver must wait only between attempts");
}

void TestResolverWaitState() {
    RuntimeController runtime;
    SequenceResolver resolver{
        ResolveStatus::kNoLoadHandle,
        ResolveStatus::kDomainUnavailable,
        ResolveStatus::kReady,
    };
    Check(runtime.TryBeginWaiting(), "wait state test must start");
    std::vector<RuntimeState> observed_states;
    modloader::RunResolverLoop(
        runtime,
        resolver,
        4,
        std::chrono::milliseconds(1),
        [&](std::chrono::milliseconds) { observed_states.push_back(runtime.state()); });

    Check(observed_states.size() == 2, "recoverable states must be retried");
    for (RuntimeState state : observed_states) {
        Check(state == RuntimeState::kWaitingForIl2Cpp,
              "recoverable resolver states must remain waiting");
    }
    Check(runtime.state() == RuntimeState::kInitializing,
          "wait state test must reach hook initialization");
}

void TestResolverTimeout() {
    RuntimeController runtime;
    SequenceResolver resolver{ResolveStatus::kNotLoaded};
    Check(runtime.TryBeginWaiting(), "timeout test must start");
    modloader::RunResolverLoop(
        runtime,
        resolver,
        3,
        std::chrono::milliseconds(1),
        [](std::chrono::milliseconds) {});

    Check(runtime.state() == RuntimeState::kFailed, "timeout must fail");
    Check(runtime.failure() == FailureCode::kIl2CppTimeout,
          "timeout must map to timeout failure");
}

void TestResolverFailures() {
    {
        RuntimeController runtime;
        SequenceResolver resolver{ResolveStatus::kRequiredSymbolMissing};
        Check(runtime.TryBeginWaiting(), "missing symbol test must start");
        modloader::RunResolverLoop(
            runtime, resolver, 3, std::chrono::milliseconds(1),
            [](std::chrono::milliseconds) {});
        Check(runtime.failure() == FailureCode::kIl2CppRequiredSymbolMissing,
              "missing symbol must map correctly");
    }
    {
        RuntimeController runtime;
        SequenceResolver resolver{ResolveStatus::kNoLoadHandle};
        Check(runtime.TryBeginWaiting(), "no handle test must start");
        modloader::RunResolverLoop(
            runtime, resolver, 2, std::chrono::milliseconds(1),
            [](std::chrono::milliseconds) {});
        Check(runtime.failure() == FailureCode::kIl2CppNoLoadHandle,
              "no handle must map correctly");
    }
    {
        RuntimeController runtime;
        SequenceResolver resolver{ResolveStatus::kDomainUnavailable};
        Check(runtime.TryBeginWaiting(), "domain test must start");
        modloader::RunResolverLoop(
            runtime, resolver, 2, std::chrono::milliseconds(1),
            [](std::chrono::milliseconds) {});
        Check(runtime.failure() == FailureCode::kIl2CppDomainUnavailable,
              "domain unavailable must map correctly");
    }
}

void TestGameProfile() {
    const auto& profile = modloader::SupportedGameProfile();
    constexpr std::uintptr_t base = 0x10000000;
    std::vector<std::uint8_t> image(0x2000000, 0);
    for (const auto& fingerprint : profile.fingerprints) {
        std::memcpy(image.data() + fingerprint.rva, fingerprint.bytes.data(),
                    fingerprint.bytes.size());
    }
    const std::uintptr_t image_base = reinterpret_cast<std::uintptr_t>(image.data());
    Check(modloader::MatchesGameProfile(profile, image_base, image_base,
                                        image_base + image.size()),
          "complete profile must match verified bytes");
    image[profile.fingerprints[0].rva] ^= 0xff;
    Check(!modloader::MatchesGameProfile(profile, image_base, image_base,
                                         image_base + image.size()),
          "one mismatched fingerprint must reject profile");
    Check(modloader::TargetAddress(profile, modloader::HookTarget::kRefreshMods, base) ==
              base + 0x1e59984,
          "profile must retain verified RefreshMods RVA");
    Check(modloader::TargetAddress(profile, modloader::HookTarget::kLoadUserMods, base) ==
              base + 0x1e59568,
          "profile must retain verified LoadUserMods RVA");
    Check(modloader::TargetAddress(profile, modloader::HookTarget::kLoadGlobalMods, base) ==
              base + 0x1e59fa4,
          "profile must retain verified LoadGlobalMods RVA");
    Check(modloader::TargetAddress(profile, modloader::HookTarget::kLoadConfig, base) ==
              base + 0x1e4f374,
          "profile must retain verified LoadConfig RVA");
    Check(modloader::TargetAddress(profile, modloader::HookTarget::kModDatabasePath, base) ==
              base + 0x1e59698,
          "profile must label MOD_DB_PATH independently from ModLoader.Run");
    Check(modloader::kOfficialModLoaderActiveModRva == 0x1e88ef0,
          "observer must retain the verified ModLoader.ActiveMod RVA");
    Check(profile.mod_loader_run.rva == 0x1e88f30,
          "profile must retain the ModLoader.Run diagnostic target");
    Check(profile.tmp_glyph.update_rva == 0x1e88b38 &&
              profile.tmp_glyph.call_rva == 0x1e88cac,
          "profile must retain the TMP glyph compatibility targets");
    Check(profile.ui_observer.panel_on_enable.rva == 0x1f1fa94 &&
              profile.ui_observer.panel_show_mods.rva == 0x1f1fb54 &&
              profile.ui_observer.panel_refresh_mods.rva == 0x1f1fd90 &&
              profile.ui_observer.item_setup.rva == 0x1f1dff0,
          "profile must retain verified UI observer targets");
}

void TestOfficialObserverValidation() {
    const auto& profile = modloader::SupportedGameProfile();
    constexpr std::uintptr_t base = 0x10000000;
    const std::uintptr_t load_global = modloader::TargetAddress(
        profile, modloader::HookTarget::kLoadGlobalMods, base);
    const std::uintptr_t active_mod =
        base + modloader::kOfficialModLoaderActiveModRva;
    const std::uintptr_t run = base + profile.mod_loader_run.rva;
    using Validation = modloader::OfficialObserverValidation;

    Check(modloader::ValidateOfficialObserverTargets(
              profile, base, load_global, active_mod, run, true) ==
              Validation::kValid,
          "verified observer targets must pass");
    Check(modloader::ValidateOfficialObserverTargets(
              profile, base, 0, active_mod, run, true) ==
              Validation::kLoadGlobalModsMethodCode,
          "missing LoadGlobalMods code must fail precisely");
    Check(modloader::ValidateOfficialObserverTargets(
              profile, base, load_global, 0, run, true) ==
              Validation::kModLoaderActiveModMethodCode,
          "missing ActiveMod code must fail precisely");
    Check(modloader::ValidateOfficialObserverTargets(
              profile, base, load_global, active_mod, 0, true) ==
              Validation::kModLoaderRunMethodCode,
          "missing Run code must fail precisely");
    Check(modloader::ValidateOfficialObserverTargets(
              profile, base, load_global + 4, active_mod, run, true) ==
              Validation::kLoadGlobalModsTarget,
          "LoadGlobalMods target drift must fail precisely");
    Check(modloader::ValidateOfficialObserverTargets(
              profile, base, load_global, active_mod + 4, run, true) ==
              Validation::kModLoaderActiveModTarget,
          "ActiveMod target drift must fail precisely");
    Check(modloader::ValidateOfficialObserverTargets(
              profile, base, load_global, active_mod, run + 4, true) ==
              Validation::kModLoaderRunTarget,
          "Run target drift must fail precisely");
    Check(modloader::ValidateOfficialObserverTargets(
              profile, base, load_global, active_mod, run, false) ==
              Validation::kModLoaderRunFingerprint,
          "Run fingerprint drift must fail precisely");
    Check(std::string(modloader::OfficialObserverValidationReason(
              Validation::kModLoaderRunTarget)) == "mod_loader_run_target",
          "observer validation reason must be stable and address-free");
    Check(modloader::ValidateOfficialObserverTargets(
              profile, static_cast<std::uintptr_t>(-2), load_global,
              active_mod, run, true) == Validation::kLoadGlobalModsTarget,
          "overflowed target arithmetic must fail closed");
}

void TestOfficialUiObserverValidation() {
    const auto& profile = modloader::SupportedGameProfile();
    constexpr std::uintptr_t base = 0x10000000;
    const auto& targets = profile.ui_observer;
    const std::uintptr_t on_enable = base + targets.panel_on_enable.rva;
    const std::uintptr_t show_mods = base + targets.panel_show_mods.rva;
    const std::uintptr_t refresh_mods = base + targets.panel_refresh_mods.rva;
    const std::uintptr_t item_setup = base + targets.item_setup.rva;
    using Validation = modloader::OfficialUiObserverValidation;

    Check(modloader::ValidateOfficialUiObserverTargets(
              profile, base, on_enable, show_mods, refresh_mods, item_setup,
              true, true, true, true) == Validation::kValid,
          "verified UI observer targets must pass");
    Check(modloader::ValidateOfficialUiObserverTargets(
              profile, base, 0, show_mods, refresh_mods, item_setup,
              true, true, true, true) == Validation::kPanelOnEnableMethodCode,
          "missing panel OnEnable code must fail precisely");
    Check(modloader::ValidateOfficialUiObserverTargets(
              profile, base, on_enable, 0, refresh_mods, item_setup,
              true, true, true, true) == Validation::kPanelShowModsMethodCode,
          "missing ShowMods code must fail precisely");
    Check(modloader::ValidateOfficialUiObserverTargets(
              profile, base, on_enable, show_mods, 0, item_setup,
              true, true, true, true) == Validation::kPanelRefreshModsMethodCode,
          "missing RefreshMods code must fail precisely");
    Check(modloader::ValidateOfficialUiObserverTargets(
              profile, base, on_enable, show_mods, refresh_mods, 0,
              true, true, true, true) == Validation::kItemSetupMethodCode,
          "missing item Setup code must fail precisely");
    Check(modloader::ValidateOfficialUiObserverTargets(
              profile, base, on_enable + 4, show_mods, refresh_mods, item_setup,
              true, true, true, true) == Validation::kPanelOnEnableTarget,
          "panel OnEnable target drift must fail precisely");
    Check(modloader::ValidateOfficialUiObserverTargets(
              profile, base, on_enable, show_mods + 4, refresh_mods, item_setup,
              true, true, true, true) == Validation::kPanelShowModsTarget,
          "ShowMods target drift must fail precisely");
    Check(modloader::ValidateOfficialUiObserverTargets(
              profile, base, on_enable, show_mods, refresh_mods + 4, item_setup,
              true, true, true, true) == Validation::kPanelRefreshModsTarget,
          "RefreshMods target drift must fail precisely");
    Check(modloader::ValidateOfficialUiObserverTargets(
              profile, base, on_enable, show_mods, refresh_mods, item_setup + 4,
              true, true, true, true) == Validation::kItemSetupTarget,
          "Setup target drift must fail despite matching RefreshMods bytes");
    Check(modloader::ValidateOfficialUiObserverTargets(
              profile, base, on_enable, show_mods, refresh_mods, item_setup,
              false, true, true, true) == Validation::kPanelOnEnableFingerprint,
          "panel OnEnable fingerprint drift must fail precisely");
    Check(modloader::ValidateOfficialUiObserverTargets(
              profile, base, on_enable, show_mods, refresh_mods, item_setup,
              true, false, true, true) == Validation::kPanelShowModsFingerprint,
          "ShowMods fingerprint drift must fail precisely");
    Check(modloader::ValidateOfficialUiObserverTargets(
              profile, base, on_enable, show_mods, refresh_mods, item_setup,
              true, true, false, true) == Validation::kPanelRefreshModsFingerprint,
          "RefreshMods fingerprint drift must fail precisely");
    Check(modloader::ValidateOfficialUiObserverTargets(
              profile, base, on_enable, show_mods, refresh_mods, item_setup,
              true, true, true, false) == Validation::kItemSetupFingerprint,
          "Setup fingerprint drift must fail precisely");
    Check(std::string(modloader::OfficialUiObserverValidationReason(
              Validation::kItemSetupTarget)) == "item_setup_target",
          "UI observer validation reason must be stable and address-free");
    Check(modloader::ValidateOfficialUiObserverTargets(
              profile, static_cast<std::uintptr_t>(-2), on_enable, show_mods,
              refresh_mods, item_setup, true, true, true, true) ==
              Validation::kPanelOnEnableTarget,
          "overflowed UI target arithmetic must fail closed");
}

void TestLifecycleGate() {
    std::vector<std::function<void()>> callbacks;
    int ready_count = 0;
    modloader::LifecycleGate gate(
        [&](std::chrono::milliseconds delay, std::function<void()> callback) {
            Check(delay == std::chrono::milliseconds(1500), "lifecycle delay must remain 1500ms");
            callbacks.push_back(std::move(callback));
        },
        [&]() { ++ready_count; });

    gate.ObserveRitePostProcess();
    Check(callbacks.empty(), "rite alone must not schedule injection");
    gate.ObserveEventPostProcess();
    Check(callbacks.size() == 1, "both post-processes must schedule injection");
    gate.ObserveEventPostProcess();
    Check(callbacks.size() == 2, "repeated completion must debounce injection");
    callbacks[0]();
    Check(ready_count == 0, "superseded debounce must not fire");
    callbacks[1]();
    Check(ready_count == 1, "latest debounce must fire once");
    gate.ObserveRitePostProcess();
    Check(callbacks.size() == 2, "completed lifecycle must not reschedule");
}

void TestConfigCatalog() {
    Check(modloader::IntegerSingleFileConfigs().size() == 9,
          "integer config catalog must match the Frida baseline");
    Check(modloader::StringSingleFileConfigs().size() == 5,
          "string config catalog must match the Frida baseline");
    Check(modloader::DirectoryConfigs().size() == 6,
          "directory config catalog must match the Frida baseline");
    Check(modloader::SingleObjectConfigs().size() == 3,
          "single-object config catalog must include the release baseline");
    Check(!modloader::IsExplicitlyUnsupportedConfig("variable.json"),
          "variable merge must be routed to the single-object pipeline");
    Check(!modloader::IsExplicitlyUnsupportedConfig("credits.json"),
          "credits merge must be routed to the single-object pipeline");
    Check(!modloader::IsExplicitlyUnsupportedConfig("sfx_config.json"),
          "sfx config merge must be routed to the single-object pipeline");
    Check(!modloader::IsExplicitlyUnsupportedConfig("cards.json"),
          "supported config must not be marked unsupported");

    const std::string json = R"json({
        // a top-level card
        "2000210": {"nested": {"999": 1}, "text": "escaped \\"123\\""},
        /* duplicate keys are applied once */
        "2000210": {},
        "2147483647": {},
        "2147483648": {},
        "not-an-id": {}
    })json";
    const std::vector<std::int32_t> ids = modloader::DiscoverTopLevelIntegerKeys(json);
    Check(ids.size() == 2 && ids[0] == 2000210 && ids[1] == 2147483647,
          "integer key scanner must ignore nested, duplicate, and out-of-range keys");

    const std::string string_json = R"json({
        // string dictionary keys
        "plain": {"nested": 1},
        "escaped\"key": {},
        "unicode中": {},
        "plain": {}
    })json";
    const std::vector<std::string> keys = modloader::DiscoverTopLevelStringKeys(string_json);
    Check(keys.size() == 3 && keys[0] == "plain" && keys[1] == "escaped\"key" &&
              keys[2] == "unicode中",
          "string key scanner must decode escapes and ignore duplicate nested keys");
}

void TestCanonicalDictionaryTransaction() {
    std::unordered_map<std::string, void*> dictionary = {
        {"existing", reinterpret_cast<void*>(0x10)},
    };
    bool reject_verification = false;
    modloader::DictionaryOperations operations{
        [&](const std::string& key, bool* exists, void** value) {
            const auto iterator = dictionary.find(key);
            *exists = iterator != dictionary.end();
            *value = *exists ? iterator->second : nullptr;
            return true;
        },
        [&](const std::string& key, void* value) {
            dictionary[key] = value;
            return true;
        },
        [&](const std::string& key) { return dictionary.erase(key) == 1; },
        [&](const std::string& key, void* value) {
            const auto iterator = dictionary.find(key);
            return iterator != dictionary.end() && iterator->second == value &&
                (!reject_verification || value != reinterpret_cast<void*>(0x50));
        },
        [&](const std::string& key) { return dictionary.find(key) == dictionary.end(); },
    };

    modloader::CanonicalDictionaryTransaction committed(operations);
    Check(committed.Apply("existing", reinterpret_cast<void*>(0x20)),
          "transaction must support an overwrite");
    Check(committed.Apply("new", reinterpret_cast<void*>(0x30)),
          "transaction must support an insertion");
    Check(committed.Commit() == modloader::TransactionResult::kCommitted,
          "transaction commit must succeed");
    Check(dictionary["existing"] == reinterpret_cast<void*>(0x20),
          "committed overwrite must persist");
    Check(dictionary["new"] == reinterpret_cast<void*>(0x30),
          "committed insertion must persist");

    modloader::CanonicalDictionaryTransaction rolled_back(operations);
    Check(rolled_back.Apply("existing", reinterpret_cast<void*>(0x40)),
          "rollback transaction must begin with a valid overwrite");
    reject_verification = true;
    Check(!rolled_back.Apply("newer", reinterpret_cast<void*>(0x50)),
          "failed verification must reject the transaction");
    reject_verification = false;
    Check(rolled_back.result() == modloader::TransactionResult::kRolledBack,
          "failed transaction must roll back automatically");
    Check(dictionary["existing"] == reinterpret_cast<void*>(0x20),
          "rollback must restore an overwritten entry");
    Check(dictionary.find("newer") == dictionary.end(),
          "rollback must remove a newly inserted entry");
}

void TestCanonicalIntDictionaryTransaction() {
    std::unordered_map<std::int32_t, void*> dictionary = {
        {2000210, reinterpret_cast<void*>(0x10)},
    };
    bool reject_verification = false;
    modloader::IntDictionaryOperations operations{
        [&](std::int32_t key, bool* exists, void** value) {
            const auto iterator = dictionary.find(key);
            *exists = iterator != dictionary.end();
            *value = *exists ? iterator->second : nullptr;
            return true;
        },
        [&](std::int32_t key, void* value) {
            dictionary[key] = value;
            return true;
        },
        [&](std::int32_t key) { return dictionary.erase(key) == 1; },
        [&](std::int32_t key, void* value) {
            const auto iterator = dictionary.find(key);
            return iterator != dictionary.end() && iterator->second == value &&
                (!reject_verification || value != reinterpret_cast<void*>(0x50));
        },
        [&](std::int32_t key) { return dictionary.find(key) == dictionary.end(); },
    };

    modloader::CanonicalIntDictionaryTransaction committed(operations);
    Check(committed.Apply(2000210, reinterpret_cast<void*>(0x20)),
          "integer transaction must overwrite an existing entry");
    Check(committed.Apply(9000001, reinterpret_cast<void*>(0x30)),
          "integer transaction must insert a new entry");
    Check(committed.Commit() == modloader::TransactionResult::kCommitted,
          "integer transaction commit must succeed");

    modloader::CanonicalIntDictionaryTransaction rolled_back(operations);
    Check(rolled_back.Apply(2000210, reinterpret_cast<void*>(0x40)),
          "integer rollback must start with a valid overwrite");
    reject_verification = true;
    Check(!rolled_back.Apply(9000002, reinterpret_cast<void*>(0x50)),
          "integer verification failure must reject the transaction");
    reject_verification = false;
    Check(rolled_back.result() == modloader::TransactionResult::kRolledBack,
          "integer transaction must roll back automatically");
    Check(dictionary[2000210] == reinterpret_cast<void*>(0x20),
          "integer rollback must restore an overwritten entry");
    Check(dictionary.find(9000002) == dictionary.end(),
          "integer rollback must verify removal of a new entry");
}

void TestResourceUriModes() {
    constexpr std::string_view root = "/storage/emulated/0/Android/data/pkg/files/Mod";
    constexpr std::string_view path =
        "/storage/emulated/0/Android/data/pkg/files/Mod/a/image/card.png";
    Check(modloader::MakeOfficialResourceArgument(
              path, root, modloader::ResourceArgumentMode::kFileUri) ==
              "file:///storage/emulated/0/Android/data/pkg/files/Mod/a/image/card.png",
          "file URI mode must use three-slash absolute URI");
    Check(modloader::MakeOfficialResourceArgument(
              path, root, modloader::ResourceArgumentMode::kAbsolutePath) == path,
          "immediate mode must preserve absolute path");
    Check(!modloader::MakeOfficialResourceArgument(
              "/storage/emulated/0/Android/data/pkg/files/Mod/../outside.png", root,
              modloader::ResourceArgumentMode::kFileUri).has_value(),
          "unsafe resource path must be rejected");
    Check(!modloader::MakeOfficialResourceArgument(
              "/storage/emulated/0/Android/data/pkg/files/Other/x.png", root,
              modloader::ResourceArgumentMode::kFileUri).has_value(),
          "resource outside Mod root must be rejected");
}

void TestResourceOverrideIndex() {
    modloader::ModFileIndex index;
    index.mods = {
        {"a-first", "/mods/a-first", {},
         {{"image/cards/one.png", "/mods/a-first/image/cards/one.png", 1},
          {"image/head/king.png", "/mods/a-first/image/head/king.png", 1}},
         {{"bgm/theme.wav", "/mods/a-first/bgm/theme.wav", 1}}},
        {"z-last", "/mods/z-last", {},
         {{"image/cards/one.png", "/mods/z-last/image/cards/one.png", 1}},
         {{"bgm/theme.ogg", "/mods/z-last/bgm/theme.ogg", 1}}},
    };
    const modloader::ResourceOverrideIndex overrides =
        modloader::BuildResourceOverrideIndex(index);
    Check(overrides.images.size() == 2, "image overrides must retain unique normalized keys");
    Check(overrides.images.at("cards/one").absolute_path ==
              "/mods/z-last/image/cards/one.png",
          "later lexical mod must override an earlier image key");
    Check(overrides.images.at("cards/one").mod_name == "z-last" &&
              overrides.image_collisions.size() == 1 &&
              overrides.image_collisions[0].previous.mod_name == "a-first",
          "image override must retain winner and collision provenance");
    Check(overrides.images.at("head/king").absolute_path ==
              "/mods/a-first/image/head/king.png",
          "non-conflicting image must remain indexed");
    Check(overrides.audio.size() == 1 &&
              overrides.audio.at("theme").absolute_path ==
              "/mods/z-last/bgm/theme.ogg",
          "later lexical mod must override an earlier audio key");
}

void TestModFileIndex() {
    const auto nonce = std::chrono::steady_clock::now().time_since_epoch().count();
    const auto root = std::filesystem::temp_directory_path() /
        ("modloader-core-tests-" + std::to_string(nonce));
    std::error_code error;
    std::filesystem::create_directories(root / "z-last" / "config", error);
    std::filesystem::create_directories(root / "a-first" / "image" / "cards", error);
    std::filesystem::create_directories(root / "a-first" / "bgm", error);
    std::filesystem::create_directories(root / "a-first" / "config", error);
    {
        std::ofstream(root / "z-last" / "config" / "cards.json") << "{}";
        std::ofstream(root / "a-first" / "config" / "tag.json") << "{}";
        std::ofstream(root / "a-first" / "image" / "cards" / "one.png") << "png";
        std::ofstream(root / "a-first" / "image" / "cards" / "two.PNG") << "png";
        std::ofstream(root / "a-first" / "image" / "cards" / "ignored.PnG") << "png";
        std::ofstream(root / "a-first" / "bgm" / "theme.wav") << "wav";
        std::ofstream(root / "a-first" / "bgm" / "voice.OGG") << "ogg";
        std::ofstream large_audio(root / "a-first" / "bgm" / "large.wav", std::ios::binary);
        large_audio.seekp(35U * 1024U * 1024U - 1);
        large_audio.put('\0');
        std::ofstream large_json(root / "a-first" / "config" / "too-large.json", std::ios::binary);
        large_json.seekp(16U * 1024U * 1024U);
        large_json.put('\0');
        std::ofstream(root / "a-first" / "ignored.txt") << "ignored";
    }

    const modloader::ModFileIndex index = modloader::ScanModRoot(root.string());
    Check(index.mods.size() == 2, "index must discover directory mods");
    Check(index.mods[0].name == "a-first" && index.mods[1].name == "z-last",
          "mods must be processed in stable lexical order");
    Check(index.mods[0].config_files.size() == 1 &&
              index.mods[0].config_files[0].relative_path == "config/tag.json",
          "index must retain the 16 MiB configuration boundary");
    Check(index.mods[0].image_files.size() == 3 &&
              index.mods[0].image_files[0].relative_path == "image/cards/ignored.PnG" &&
              index.mods[0].image_files[1].relative_path == "image/cards/one.png" &&
              index.mods[0].image_files[2].relative_path == "image/cards/two.PNG",
          "index must accept PNG override files case-insensitively");
    Check(index.mods[0].audio_files.size() == 3 &&
              index.mods[0].audio_files[0].relative_path == "bgm/large.wav" &&
              index.mods[0].audio_files[0].size == 35U * 1024U * 1024U &&
              index.mods[0].audio_files[1].relative_path == "bgm/theme.wav" &&
              index.mods[0].audio_files[2].relative_path == "bgm/voice.OGG",
          "index must accept supported media case-insensitively up to the media limit");
    const auto contents = modloader::ReadIndexedFile(index.mods[0].config_files[0], 64);
    Check(contents.has_value() && *contents == "{}", "indexed file must be read exactly");
    Check(!modloader::ReadIndexedFile(index.mods[0].config_files[0], 1).has_value(),
          "read must enforce caller file limits");
}

}  // namespace

int main() {
    TestBackendRoute();
    TestReadyBackendRouteIsTerminal();
    TestOfficialCanaryCompletion();
    TestOfficialCanaryPromiseState();
    TestModPath();
    TestSingleStarter();
    TestReadyTerminalState();
    TestUnsupportedTerminalState();
    TestFailureSnapshotConsistency();
    TestResolverSuccess();
    TestResolverWaitState();
    TestResolverTimeout();
    TestResolverFailures();
    TestGameProfile();
    TestOfficialObserverValidation();
    TestOfficialUiObserverValidation();
    TestLifecycleGate();
    TestConfigCatalog();
    TestCanonicalDictionaryTransaction();
    TestCanonicalIntDictionaryTransaction();
    TestResourceUriModes();
    TestResourceOverrideIndex();
    TestModFileIndex();

    if (failures != 0) {
        std::cerr << failures << " test assertion(s) failed\n";
        return EXIT_FAILURE;
    }
    std::cout << "modloader_core_tests passed\n";
    return EXIT_SUCCESS;
}
