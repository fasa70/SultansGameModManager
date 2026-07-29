#include "modloader/android_log.h"
#include "modloader/bootstrap_notify.h"
#include "modloader/il2cpp_api.h"
#include "modloader/mod_hooks.h"
#include "modloader/mod_path.h"
#include "modloader/mod_root.h"
#include "modloader/runtime_state.h"

#include <jni.h>

#include <chrono>
#include <exception>
#include <mutex>
#include <string>
#include <thread>

namespace modloader {
namespace {

constexpr char kBootstrapClass[] =
    "com/gametree/sultan/pd/mod/ModLoaderBootstrap";
constexpr std::size_t kResolveAttempts = 150;
constexpr auto kResolveInterval = std::chrono::milliseconds(100);

JavaVM* g_vm = nullptr;
RuntimeController g_runtime;
std::mutex g_bootstrap_mutex;
jobject g_application_context = nullptr;

void ClearPendingException(JNIEnv* env) {
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
}

std::string ReadExternalFilesPath(JNIEnv* env, jobject context) {
    jclass context_class = env->GetObjectClass(context);
    if (context_class == nullptr) {
        ClearPendingException(env);
        return {};
    }

    jmethodID get_external_files_dir = env->GetMethodID(
        context_class, "getExternalFilesDir", "(Ljava/lang/String;)Ljava/io/File;");
    env->DeleteLocalRef(context_class);
    if (get_external_files_dir == nullptr) {
        ClearPendingException(env);
        return {};
    }

    jobject file = env->CallObjectMethod(context, get_external_files_dir, nullptr);
    if (env->ExceptionCheck() || file == nullptr) {
        ClearPendingException(env);
        return {};
    }

    jclass file_class = env->GetObjectClass(file);
    jmethodID get_absolute_path = file_class == nullptr
        ? nullptr
        : env->GetMethodID(file_class, "getAbsolutePath", "()Ljava/lang/String;");
    if (file_class != nullptr) {
        env->DeleteLocalRef(file_class);
    }
    if (get_absolute_path == nullptr) {
        env->DeleteLocalRef(file);
        ClearPendingException(env);
        return {};
    }

    auto path = static_cast<jstring>(env->CallObjectMethod(file, get_absolute_path));
    env->DeleteLocalRef(file);
    if (env->ExceptionCheck() || path == nullptr) {
        ClearPendingException(env);
        return {};
    }

    const char* utf8 = env->GetStringUTFChars(path, nullptr);
    if (utf8 == nullptr) {
        env->DeleteLocalRef(path);
        ClearPendingException(env);
        return {};
    }
    std::string result(utf8);
    env->ReleaseStringUTFChars(path, utf8);
    env->DeleteLocalRef(path);
    return result;
}

jobject GetApplicationContext(JNIEnv* env, jobject context) {
    jclass context_class = env->GetObjectClass(context);
    if (context_class == nullptr) {
        ClearPendingException(env);
        return nullptr;
    }
    jmethodID method = env->GetMethodID(
        context_class, "getApplicationContext", "()Landroid/content/Context;");
    env->DeleteLocalRef(context_class);
    if (method == nullptr) {
        ClearPendingException(env);
        return nullptr;
    }

    jobject application = env->CallObjectMethod(context, method);
    if (env->ExceptionCheck()) {
        ClearPendingException(env);
        return nullptr;
    }
    return application;
}

void ResolverWorker() noexcept {
    try {
        RunResolverLoop(
            g_runtime,
            GetAndroidIl2CppResolver(),
            kResolveAttempts,
            kResolveInterval,
            [](std::chrono::milliseconds interval) {
                std::this_thread::sleep_for(interval);
            });
        if (g_runtime.state() == RuntimeState::kInitializing) {
            const Il2CppApi* api = GetResolvedIl2CppApi();
            if (api == nullptr || !InstallModHooks(*api, g_runtime)) {
                if (g_runtime.state() == RuntimeState::kInitializing) {
                    g_runtime.Fail(FailureCode::kUnexpectedNativeException);
                }
            }
        }
    } catch (...) {
        g_runtime.Fail(FailureCode::kUnexpectedNativeException);
    }

    LogState(g_runtime.state());
    if (g_runtime.state() == RuntimeState::kFailed ||
        g_runtime.state() == RuntimeState::kUnsupported) {
        LogFailure(g_runtime.failure());
    }
}

jint FailBootstrap(FailureCode failure) {
    g_runtime.Fail(failure);
    LogState(g_runtime.state());
    LogFailure(failure);
    return static_cast<jint>(RuntimeState::kFailed);
}

jint NativeBootstrap(JNIEnv* env, jclass, jobject context) noexcept {
    try {
        if (context == nullptr) {
            std::lock_guard<std::mutex> lock(g_bootstrap_mutex);
            return FailBootstrap(FailureCode::kInvalidContext);
        }

        std::lock_guard<std::mutex> lock(g_bootstrap_mutex);
        if (g_runtime.state() != RuntimeState::kNotStarted) {
            return static_cast<jint>(g_runtime.state());
        }

        jobject application = GetApplicationContext(env, context);
        jobject effective_context = application == nullptr ? context : application;
        std::string external_files = ReadExternalFilesPath(env, effective_context);
        auto mod_root = BuildModRoot(external_files);
        if (!mod_root.has_value()) {
            if (application != nullptr) {
                env->DeleteLocalRef(application);
            }
            return FailBootstrap(FailureCode::kExternalFilesUnavailable);
        }

        jobject global_context = env->NewGlobalRef(effective_context);
        if (application != nullptr) {
            env->DeleteLocalRef(application);
        }
        if (global_context == nullptr) {
            ClearPendingException(env);
            return FailBootstrap(FailureCode::kInvalidContext);
        }

        if (!g_runtime.TryBeginWaiting()) {
            env->DeleteGlobalRef(global_context);
            return static_cast<jint>(g_runtime.state());
        }

        g_application_context = global_context;
        SetModRoot(std::move(*mod_root));
        LogState(RuntimeState::kWaitingForIl2Cpp);
        std::thread(ResolverWorker).detach();
        return static_cast<jint>(RuntimeState::kWaitingForIl2Cpp);
    } catch (...) {
        std::lock_guard<std::mutex> lock(g_bootstrap_mutex);
        return FailBootstrap(FailureCode::kUnexpectedNativeException);
    }
}

jint NativeGetState(JNIEnv*, jclass) noexcept {
    return static_cast<jint>(g_runtime.state());
}

jint NativeGetFailureCode(JNIEnv*, jclass) noexcept {
    return static_cast<jint>(g_runtime.failure());
}

JNINativeMethod kMethods[] = {
    {const_cast<char*>("nativeBootstrap"),
     const_cast<char*>("(Landroid/content/Context;)I"),
     reinterpret_cast<void*>(NativeBootstrap)},
    {const_cast<char*>("nativeGetState"),
     const_cast<char*>("()I"),
     reinterpret_cast<void*>(NativeGetState)},
    {const_cast<char*>("nativeGetFailureCode"),
     const_cast<char*>("()I"),
     reinterpret_cast<void*>(NativeGetFailureCode)},
};

}

void NotifyModsApplied(std::size_t count) noexcept {
    if (g_vm == nullptr) {
        return;
    }

    JNIEnv* env = nullptr;
    bool attached = false;
    const jint state = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (state == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return;
        }
        attached = true;
    } else if (state != JNI_OK || env == nullptr) {
        return;
    }

    jclass bootstrap = env->FindClass(kBootstrapClass);
    if (bootstrap == nullptr) {
        ClearPendingException(env);
    } else {
        jmethodID notify = env->GetStaticMethodID(bootstrap, "onNativeModsApplied", "(I)V");
        if (notify == nullptr) {
            ClearPendingException(env);
        } else {
            env->CallStaticVoidMethod(bootstrap, notify, static_cast<jint>(count));
            ClearPendingException(env);
        }
        env->DeleteLocalRef(bootstrap);
    }

    if (attached) {
        g_vm->DetachCurrentThread();
    }
}

}  // namespace modloader

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm == nullptr ||
        vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass bootstrap = env->FindClass(modloader::kBootstrapClass);
    if (bootstrap == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        return JNI_ERR;
    }

    const jint result = env->RegisterNatives(
        bootstrap,
        modloader::kMethods,
        sizeof(modloader::kMethods) / sizeof(modloader::kMethods[0]));
    env->DeleteLocalRef(bootstrap);
    if (result != JNI_OK) {
        return JNI_ERR;
    }

    modloader::g_vm = vm;
    return JNI_VERSION_1_6;
}
