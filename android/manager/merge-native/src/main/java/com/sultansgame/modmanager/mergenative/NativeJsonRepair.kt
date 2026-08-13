package com.sultansgame.modmanager.mergenative

/** JNI facade for the vendored upstream JsonRepairer. */
object NativeJsonRepair {
    private var loaded = false

    @JvmStatic
    fun repair(input: String): String {
        ensureLoaded()
        return nativeRepair(input)
    }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        System.loadLibrary("sultans_merge_native")
        nativeInit()
        loaded = true
    }

    @JvmStatic
    private external fun nativeRepair(input: String): String

    @JvmStatic
    private external fun nativeInit()
}
