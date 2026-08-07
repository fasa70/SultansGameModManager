package com.gametree.sultan.pd.mod;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ModLoaderBootstrap {
    public static final int LOAD_FAILED = -1;

    private static final String LOADER_ASSET = "modloader/arm64-v8a/modloader.bin";
    private static final AtomicBoolean MOD_TOAST_SHOWN = new AtomicBoolean();
    private static volatile Context applicationContext;
    private static boolean loaded;

    private ModLoaderBootstrap() {}

    public static synchronized int ensureStarted(Context context) {
        applicationContext = context.getApplicationContext();
        if (applicationContext == null) {
            applicationContext = context;
        }
        if (!loaded) {
            try {
                File directory = new File(context.getCodeCacheDir(), "sultan-modloader");
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    return LOAD_FAILED;
                }
                File library = new File(directory, "libmodloader.so");
                File temporary = new File(directory, "libmodloader.so.tmp");
                try (InputStream input = context.getAssets().open(LOADER_ASSET);
                     FileOutputStream output = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[16384];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                    }
                    output.getFD().sync();
                }
                if (library.exists() && !library.delete()) {
                    return LOAD_FAILED;
                }
                if (!temporary.renameTo(library)) {
                    return LOAD_FAILED;
                }
                System.load(library.getAbsolutePath());
                loaded = true;
            } catch (Throwable ignored) {
                return LOAD_FAILED;
            }
        }
        try {
            return nativeBootstrap(applicationContext);
        } catch (Throwable ignored) {
            return LOAD_FAILED;
        }
    }

    public static void onNativeModsApplied(int count) {
        Context context = applicationContext;
        if (context == null || !MOD_TOAST_SHOWN.compareAndSet(false, true)) {
            return;
        }
        String message = count == -1
                ? "官方 Mod 后端已完成加载"
                : count == 0 ? "未发现可加载的 Mod" : "已加载 " + count + " 个 Mod";
        new Handler(Looper.getMainLooper()).post(
            () -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        );
    }

    public static int getNativeStateForVerification() {
        if (!loaded) {
            return LOAD_FAILED;
        }
        try {
            return nativeGetState();
        } catch (Throwable ignored) {
            return LOAD_FAILED;
        }
    }

    public static int getNativeFailureCodeForVerification() {
        if (!loaded) {
            return LOAD_FAILED;
        }
        try {
            return nativeGetFailureCode();
        } catch (Throwable ignored) {
            return LOAD_FAILED;
        }
    }

    private static native int nativeBootstrap(Context context);
    private static native int nativeGetState();
    private static native int nativeGetFailureCode();
}
