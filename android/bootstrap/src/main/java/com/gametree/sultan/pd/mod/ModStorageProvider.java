package com.gametree.sultan.pd.mod;

import android.app.ActivityManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;

public final class ModStorageProvider extends ContentProvider {
    private static final int PROTOCOL_VERSION = 1;
    private static final String MANAGER_PACKAGE = "com.sultansgame.modmanager";
    private static final String PREFS = "mod-storage";
    private static final String AUTHORIZED_CERTIFICATE = "authorizedCertificate";
    private static final String REVISION = "revision";
    private static final String KEY_PROTOCOL_VERSION = "protocolVersion";
    private static final String KEY_AUTHORIZE = "authorize";
    private static final String KEY_REVISION = "revision";
    private static final String KEY_INPUT = "input";
    private static final String KEY_ALLOW_EXTERNAL_REPLACEMENT = "allowExternalReplacement";
    private static final String KEY_RESULT_CODE = "resultCode";
    private static final String KEY_RESULT_REASON = "resultReason";
    private static final String KEY_MOD_NAMES = "modNames";
    private static final String RESULT_OK = "ok";
    private static final String RESULT_UNAUTHORIZED = "unauthorized";
    private static final String RESULT_INCOMPATIBLE = "incompatible";
    private static final String RESULT_INVALID = "invalid";
    private static final String RESULT_FAILED = "failed";
    private static final String RESULT_GAME_RUNNING = "gameRunning";
    private static final String RESULT_EXTERNAL_CHANGES_DETECTED = "externalChangesDetected";
    private static final String RESULT_VALIDATION_FAILED = "validationFailed";
    private static final String RESULT_COMMIT_FAILED = "commitFailed";
    private static final int MAX_ENTRY_COUNT = 10_000;
    private static final long MAX_FILE_SIZE = 256L * 1024L * 1024L;
    private static final long MAX_TOTAL_SIZE = 1024L * 1024L * 1024L;

    @Override
    public boolean onCreate() {
        recoverInterruptedSync();
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!hasCompatibleProtocol(extras)) return result(RESULT_INCOMPATIBLE, "协议版本不兼容");
        if (!isPinnedManager()) return result(RESULT_UNAUTHORIZED, "调用方证书不受信任");
        if ("status".equals(method)) return status();
        if ("syncSnapshot".equals(method)) return syncSnapshot(extras);
        if (!isTrustedManager()) return result(RESULT_UNAUTHORIZED, "调用方未获授权");
        if ("list".equals(method)) return listMods();
        if ("revokeAuthorization".equals(method)) return revokeAuthorization();
        return result(RESULT_INVALID, "不支持的调用方法");
    }

    private boolean hasCompatibleProtocol(Bundle extras) {
        return extras != null && extras.getInt(KEY_PROTOCOL_VERSION, -1) == PROTOCOL_VERSION;
    }

    private Bundle status() {
        Bundle result = result(RESULT_OK, null);
        result.putInt(KEY_PROTOCOL_VERSION, PROTOCOL_VERSION);
        result.putString(KEY_REVISION, preferences().getString(REVISION, null));
        result.putBoolean("authorized", isTrustedManager());
        result.putStringArrayList(KEY_MOD_NAMES, modNames());
        return result;
    }

    private Bundle listMods() {
        Bundle result = result(RESULT_OK, null);
        result.putString(KEY_REVISION, preferences().getString(REVISION, null));
        result.putStringArrayList(KEY_MOD_NAMES, modNames());
        return result;
    }

    private Bundle revokeAuthorization() {
        preferences().edit().clear().apply();
        return result(RESULT_OK, null);
    }

    private Bundle syncSnapshot(Bundle extras) {
        if (!isPinnedManager()) return result(RESULT_UNAUTHORIZED, "调用方证书不受信任");
        if (!extras.getBoolean(KEY_AUTHORIZE, false) && !isAuthorized()) {
            return result(RESULT_UNAUTHORIZED, "请先在 Manager 确认允许管理游戏 Mod");
        }
        String revision = extras.getString(KEY_REVISION);
        ParcelFileDescriptor input = extras.getParcelable(KEY_INPUT);
        if (!isSafeRevision(revision) || input == null) return result(RESULT_INVALID, "同步参数无效");

        Context context = getContext();
        if (context == null) return result(RESULT_FAILED, "游戏存储不可用");
        if (isGameProcessRunning(context)) {
            return result(RESULT_GAME_RUNNING, "请完全退出游戏后再同步 Mod");
        }
        File externalFiles = context.getExternalFilesDir(null);
        if (externalFiles == null) return result(RESULT_FAILED, "游戏外部存储不可用");
        File staging = new File(externalFiles, ".Mod-staging-" + revision);
        File active = new File(externalFiles, "Mod");
        File backup = new File(externalFiles, ".Mod-backup-" + revision);
        if (hasExternalMods() && !extras.getBoolean(KEY_ALLOW_EXTERNAL_REPLACEMENT, false)) {
            return result(RESULT_EXTERNAL_CHANGES_DETECTED, "游戏目录存在外部 Mod，请在 Manager 确认后再覆盖");
        }
        if (staging.exists()) deleteRecursively(staging);
        try (DataInputStream stream = new DataInputStream(new BufferedInputStream(
                new ParcelFileDescriptor.AutoCloseInputStream(input)))) {
            copySnapshot(stream, staging);
        } catch (IOException error) {
            deleteRecursively(staging);
            return result(RESULT_VALIDATION_FAILED, error.getMessage() == null ? "Mod 数据校验失败" : error.getMessage());
        } catch (Exception error) {
            deleteRecursively(staging);
            return result(RESULT_FAILED, error.getMessage() == null ? "同步失败" : error.getMessage());
        }

        try {
            if (backup.exists()) deleteRecursively(backup);
            if (active.exists() && !active.renameTo(backup)) throw new IOException("无法备份当前 Mod 目录");
            if (!staging.renameTo(active)) {
                if (backup.exists()) backup.renameTo(active);
                throw new IOException("无法提交新的 Mod 快照");
            }
            deleteRecursively(backup);
            preferences().edit()
                    .putString(AUTHORIZED_CERTIFICATE, callerCertificate())
                    .putString(REVISION, revision)
                    .apply();
            return listMods();
        } catch (Exception error) {
            deleteRecursively(staging);
            if (!active.exists() && backup.exists()) backup.renameTo(active);
            return result(RESULT_COMMIT_FAILED, error.getMessage() == null ? "Mod 快照提交失败" : error.getMessage());
        }
    }

    private void copySnapshot(DataInputStream input, File staging) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > MAX_ENTRY_COUNT) throw new IOException("Mod 数量超出限制");
        if (!staging.mkdirs()) throw new IOException("无法创建暂存目录");
        long totalSize = 0;
        for (int index = 0; index < count; index++) {
            String directoryName = input.readUTF();
            if (!isSafeComponent(directoryName)) throw new IOException("Mod 目录名无效");
            File modRoot = new File(staging, directoryName);
            int fileCount = input.readInt();
            if (fileCount < 1 || fileCount > MAX_ENTRY_COUNT || !modRoot.mkdirs()) {
                throw new IOException("Mod 文件列表无效");
            }
            for (int fileIndex = 0; fileIndex < fileCount; fileIndex++) {
                String relativePath = input.readUTF();
                long size = input.readLong();
                String expectedDigest = input.readUTF();
                if (!isSafeRelativePath(relativePath) || size < 0 || size > MAX_FILE_SIZE ||
                        !expectedDigest.matches("[0-9a-f]{64}")) {
                    throw new IOException("Mod 文件无效");
                }
                totalSize = Math.addExact(totalSize, size);
                if (totalSize > MAX_TOTAL_SIZE) throw new IOException("Mod 总大小超出限制");
                File target = new File(modRoot, relativePath);
                File parent = target.getParentFile();
                if (parent == null || (!parent.exists() && !parent.mkdirs())) throw new IOException("无法创建 Mod 路径");
                MessageDigest digest = sha256();
                try (FileOutputStream output = new FileOutputStream(target)) {
                    byte[] buffer = new byte[16 * 1024];
                    long remaining = size;
                    while (remaining > 0) {
                        int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (read < 0) throw new IOException("Mod 数据意外结束");
                        output.write(buffer, 0, read);
                        digest.update(buffer, 0, read);
                        remaining -= read;
                    }
                    output.getFD().sync();
                }
                if (!expectedDigest.equals(hex(digest.digest()))) {
                    throw new IOException("Mod 文件摘要不匹配");
                }
            }
            if (!hasInfoJson(modRoot)) throw new IOException("Mod 缺少 Info.json");
        }
        if (input.read() != -1) throw new IOException("Mod 数据包含额外内容");
    }

    private boolean isPinnedManager() {
        String certificate = callerCertificate();
        return certificate != null && certificate.equals(BuildConfig.MANAGER_CERTIFICATE_SHA256);
    }

    private boolean isTrustedManager() {
        String certificate = callerCertificate();
        return isPinnedManager() && certificate.equals(preferences().getString(AUTHORIZED_CERTIFICATE, null));
    }

    private boolean isAuthorized() {
        return preferences().contains(AUTHORIZED_CERTIFICATE);
    }

    private String callerCertificate() {
        Context context = getContext();
        if (context == null) return null;
        int uid = Binder.getCallingUid();
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null || packages.length != 1 || !MANAGER_PACKAGE.equals(packages[0])) return null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo info = context.getPackageManager().getPackageInfo(
                        MANAGER_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
                if (info.signingInfo == null || info.signingInfo.hasMultipleSigners() ||
                        info.signingInfo.getSigningCertificateHistory().length != 1) return null;
                return hex(sha256().digest(info.signingInfo.getSigningCertificateHistory()[0].toByteArray()));
            }
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    MANAGER_PACKAGE, PackageManager.GET_SIGNATURES);
            if (info.signatures == null || info.signatures.length != 1) return null;
            return hex(sha256().digest(info.signatures[0].toByteArray()));
        } catch (PackageManager.NameNotFoundException error) {
            return null;
        }
    }

    private SharedPreferences preferences() {
        return getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private ArrayList<String> modNames() {
        Context context = getContext();
        File root = context == null ? null : new File(context.getExternalFilesDir(null), "Mod");
        ArrayList<String> names = new ArrayList<>();
        if (root == null || !root.isDirectory()) return names;
        File[] children = root.listFiles();
        if (children == null) return names;
        Arrays.sort(children, Comparator.comparing(File::getName));
        for (File child : children) if (child.isDirectory() && isSafeComponent(child.getName())) names.add(child.getName());
        return names;
    }

    private boolean isGameProcessRunning(Context context) {
        ActivityManager activityManager = context.getSystemService(ActivityManager.class);
        if (activityManager == null) return false;
        String processName = context.getPackageName();
        for (ActivityManager.RunningAppProcessInfo process : activityManager.getRunningAppProcesses()) {
            if (processName.equals(process.processName)) return true;
        }
        return false;
    }

    private boolean hasExternalMods() {
        for (String name : modNames()) {
            if (!name.matches("\\d{6}--[0-9a-f]{64}")) return true;
        }
        return false;
    }

    private void recoverInterruptedSync() {
        Context context = getContext();
        if (context == null) return;
        File externalFiles = context.getExternalFilesDir(null);
        if (externalFiles == null) return;
        File active = new File(externalFiles, "Mod");
        File[] remnants = externalFiles.listFiles((dir, name) -> name.startsWith(".Mod-staging-") || name.startsWith(".Mod-backup-"));
        if (remnants == null) return;
        for (File remnant : remnants) {
            if (remnant.getName().startsWith(".Mod-backup-") && !active.exists()) remnant.renameTo(active);
            else deleteRecursively(remnant);
        }
    }

    private static Bundle result(String code, String reason) {
        Bundle result = new Bundle();
        result.putString(KEY_RESULT_CODE, code);
        result.putString(KEY_RESULT_REASON, reason);
        return result;
    }

    private static boolean isSafeRevision(String value) {
        return value != null && value.matches("[0-9a-f-]{36}");
    }

    private static boolean isSafeComponent(String value) {
        return value != null && !value.isEmpty() && !".".equals(value) && !"..".equals(value) &&
                value.indexOf('/') < 0 && value.indexOf('\\') < 0 && value.indexOf('\0') < 0;
    }

    private static boolean isSafeRelativePath(String value) {
        if (value == null || value.isEmpty() || value.startsWith("/") || value.startsWith("\\")) return false;
        String[] components = value.split("/");
        if (components.length > 8) return false;
        for (String component : components) if (!isSafeComponent(component)) return false;
        return true;
    }

    private static boolean hasInfoJson(File root) {
        File[] entries = root.listFiles();
        if (entries == null) return false;
        int matches = 0;
        for (File entry : entries) if (entry.isFile() && "info.json".equalsIgnoreCase(entry.getName())) matches++;
        return matches == 1;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }

    private static void deleteRecursively(File root) {
        if (root.isDirectory()) {
            File[] children = root.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        root.delete();
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
