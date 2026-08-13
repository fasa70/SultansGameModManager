#include <jni.h>

#include <string>

#include "json_cleaner.h"

namespace {

jclass find_exception(JNIEnv* env) {
    return env->FindClass("java/lang/IllegalArgumentException");
}

jstring to_java_string(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_sultansgame_modmanager_mergenative_NativeJsonRepair_nativeRepair(
    JNIEnv* env,
    jclass,
    jstring input) {
    if (input == nullptr) {
        env->ThrowNew(find_exception(env), "JSON input is null");
        return nullptr;
    }

    const char* chars = env->GetStringUTFChars(input, nullptr);
    if (chars == nullptr) return nullptr;
    std::string source(chars);
    env->ReleaseStringUTFChars(input, chars);

    try {
        return to_java_string(env, sultan::clean_text(source).text);
    } catch (const std::exception& error) {
        env->ThrowNew(find_exception(env), error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_sultansgame_modmanager_mergenative_NativeJsonRepair_nativeInit(
    JNIEnv*,
    jclass) {}
