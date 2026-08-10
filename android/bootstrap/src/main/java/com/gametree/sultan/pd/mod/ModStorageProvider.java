package com.gametree.sultan.pd.mod;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
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
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;

public final class ModStorageProvider extends ContentProvider {
    private static final int PROTOCOL_VERSION = 2;
    private static final String MANAGER_PACKAGE = "com.sultansgame.modmanager";
    private static final String MANAGER_DIRECTORY_PREFIX = "sgmm-";
    private static final String KEY_PROTOCOL_VERSION = "protocolVersion";
    private static final String KEY_CACHE_KEY = "cacheKey";
    private static final String KEY_INPUT = "input";
    private static final String KEY_RESULT_CODE = "resultCode";
    private static final String KEY_RESULT_REASON = "resultReason";
    private static final String KEY_MOD_NAMES = "modNames";
    private static final String KEY_MANAGER_CACHE_KEYS = "managerCacheKeys";
    private static final String RESULT_OK = "ok";
    private static final String RESULT_UNAUTHORIZED = "unauthorized";
    private static final String RESULT_INCOMPATIBLE = "incompatible";
    private static final String RESULT_INVALID = "invalid";
    private static final String RESULT_FAILED = "failed";
    private static final String RESULT_VALIDATION_FAILED = "validationFailed";
    private static final String RESULT_COMMIT_FAILED = "commitFailed";
    private static final int MAX_ENTRY_COUNT = 10_000;
    private static final long MAX_FILE_SIZE = 256L * 1024L * 1024L;
    private static final long MAX_TOTAL_SIZE = 1024L * 1024L * 1024L;

    @Override
    public boolean onCreate() {
        recoverInterruptedSyncs();
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        ParcelFileDescriptor input = extras == null ? null : extras.getParcelable(KEY_INPUT);
        try {
            if (!hasCompatibleProtocol(extras)) return result(RESULT_INCOMPATIBLE, "协议版本不兼容，请重新修补游戏");
            if (!isPinnedManager()) return result(RESULT_UNAUTHORIZED, "调用方证书不受信任，请重新修补游戏");
            if ("listMods".equals(method)) return listMods();
            if ("syncMod".equals(method)) return syncMod(extras, input);
            if ("removeManagedMod".equals(method)) return removeManagedMod(extras);
            return result(RESULT_INVALID, "不支持的调用方法");
        } finally {
            closeQuietly(input);
        }
    }

    private boolean hasCompatibleProtocol(Bundle extras) {
        return extras != null && extras.getInt(KEY_PROTOCOL_VERSION, -1) == PROTOCOL_VERSION;
    }

    private synchronized Bundle syncMod(Bundle extras, ParcelFileDescriptor input) {
        String cacheKey = extras == null ? null : extras.getString(KEY_CACHE_KEY);
        if (!isCacheKey(cacheKey) || input == null) return result(RESULT_INVALID, "同步参数无效");
        Context context = getContext();
        if (context == null) return result(RESULT_FAILED, "游戏存储不可用");
        File externalFiles = context.getExternalFilesDir(null);
        if (externalFiles == null) return result(RESULT_FAILED, "游戏外部存储不可用");
        File modRoot = new File(externalFiles, "Mod");
        if (!modRoot.exists() && !modRoot.mkdirs()) return result(RESULT_FAILED, "无法创建 Mod 目录");
        if (!modRoot.isDirectory()) return result(RESULT_FAILED, "游戏 Mod 路径不可用");

        File staging = new File(externalFiles, ".sgmm-staging-" + cacheKey + "-" + UUID.randomUUID());
        File target = new File(modRoot, managedDirectoryName(cacheKey));
        File backup = new File(externalFiles, ".sgmm-backup-" + cacheKey);
        if (target.exists() && !hasManagedMarker(target, cacheKey)) {
            return result(RESULT_COMMIT_FAILED, "目标目录不是 Manager 管理的 Mod，已拒绝覆盖");
        }
        try (DataInputStream stream = new DataInputStream(new BufferedInputStream(
                new ParcelFileDescriptor.AutoCloseInputStream(input)))) {
            copyMod(stream, staging, cacheKey);
        } catch (IOException error) {
            deleteRecursively(staging);
            return result(RESULT_VALIDATION_FAILED,
                    error.getMessage() == null ? "Mod 数据校验失败" : error.getMessage());
        } catch (Exception error) {
            deleteRecursively(staging);
            return result(RESULT_FAILED, error.getMessage() == null ? "同步失败" : error.getMessage());
        }

        try {
            if (backup.exists()) deleteRecursively(backup);
            if (target.exists() && !target.renameTo(backup)) {
                throw new IOException("无法备份现有 Manager Mod");
            }
            if (!staging.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target);
                throw new IOException("无法同步 Mod");
            }
            deleteRecursively(backup);
            deleteLegacyManagedDirectories(modRoot, cacheKey);
            return listMods();
        } catch (Exception error) {
            deleteRecursively(staging);
            if (!target.exists() && backup.exists()) backup.renameTo(target);
            return result(RESULT_COMMIT_FAILED,
                    error.getMessage() == null ? "Mod 同步失败" : error.getMessage());
        }
    }

    private synchronized Bundle removeManagedMod(Bundle extras) {
        String cacheKey = extras == null ? null : extras.getString(KEY_CACHE_KEY);
        if (!isCacheKey(cacheKey)) return result(RESULT_INVALID, "Mod 标识无效");
        Context context = getContext();
        if (context == null) return result(RESULT_FAILED, "游戏存储不可用");
        File externalFiles = context.getExternalFilesDir(null);
        if (externalFiles == null) return result(RESULT_FAILED, "游戏外部存储不可用");
        File target = new File(new File(externalFiles, "Mod"), managedDirectoryName(cacheKey));
        if (target.exists() && (!target.isDirectory() || !hasManagedMarker(target, cacheKey))) {
            return result(RESULT_COMMIT_FAILED, "目标目录不是 Manager 管理的 Mod，已拒绝删除");
        }
        if (target.exists() && (!deleteRecursively(target) || target.exists())) {
            return result(RESULT_COMMIT_FAILED, "无法从游戏目录删除 Manager Mod");
        }
        File modRoot = target.getParentFile();
        if (modRoot != null) deleteLegacyManagedDirectories(modRoot, cacheKey);
        return listMods();
    }

    private Bundle listMods() {
        Context context = getContext();
        File root = context == null ? null : new File(context.getExternalFilesDir(null), "Mod");
        ArrayList<String> names = new ArrayList<>();
        ArrayList<String> managerKeys = new ArrayList<>();
        if (root != null && root.isDirectory()) {
            File[] children = root.listFiles();
            if (children != null) {
                Arrays.sort(children, Comparator.comparing(File::getName));
                for (File child : children) {
                    if (!child.isDirectory() || !isSafeComponent(child.getName())) continue;
                    names.add(child.getName());
                    String cacheKey = managerCacheKey(child.getName());
                    managerKeys.add(cacheKey != null && hasManagedMarker(child, cacheKey) ? cacheKey : "");
                }
            }
        }
        Bundle result = result(RESULT_OK, null);
        result.putStringArrayList(KEY_MOD_NAMES, names);
        result.putStringArrayList(KEY_MANAGER_CACHE_KEYS, managerKeys);
        return result;
    }

    private void copyMod(DataInputStream input, File staging, String cacheKey) throws IOException {
        int count = input.readInt();
        if (count < 1 || count > MAX_ENTRY_COUNT) throw new IOException("Mod 文件列表无效");
        if (!staging.mkdirs()) throw new IOException("无法创建暂存目录");
        long totalSize = 0;
        for (int fileIndex = 0; fileIndex < count; fileIndex++) {
            String relativePath = input.readUTF();
            long size = input.readLong();
            String expectedDigest = input.readUTF();
            if (!isSafeRelativePath(relativePath) || size < 0 || size > MAX_FILE_SIZE ||
                    !expectedDigest.matches("[0-9a-f]{64}")) {
                throw new IOException("Mod 文件无效");
            }
            totalSize = Math.addExact(totalSize, size);
            if (totalSize > MAX_TOTAL_SIZE) throw new IOException("Mod 总大小超出限制");
            File target = new File(staging, relativePath);
            File parent = target.getParentFile();
            if (parent == null || (!parent.exists() && !parent.mkdirs())) {
                throw new IOException("无法创建 Mod 路径");
            }
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
        if (input.read() != -1) throw new IOException("Mod 数据包含额外内容");
        if (!hasInfoJson(staging)) throw new IOException("Mod 缺少 Info.json");
        writeManagedMarker(staging, cacheKey);
    }

    private static void writeManagedMarker(File root, String cacheKey) throws IOException {
        File marker = new File(root, ".sgmm-owner");
        try (FileOutputStream output = new FileOutputStream(marker)) {
            output.write(cacheKey.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            output.getFD().sync();
        }
    }

    private static boolean hasManagedMarker(File root, String cacheKey) {
        File marker = new File(root, ".sgmm-owner");
        if (!marker.isFile() || marker.length() != cacheKey.length()) return false;
        try (java.io.FileInputStream input = new java.io.FileInputStream(marker)) {
            byte[] data = new byte[cacheKey.length()];
            if (input.read(data) != data.length || input.read() != -1) return false;
            return cacheKey.equals(new String(data, java.nio.charset.StandardCharsets.US_ASCII));
        } catch (IOException error) {
            return false;
        }
    }

    private boolean isPinnedManager() {
        String certificate = callerCertificate();
        return certificate != null && certificate.equals(BuildConfig.MANAGER_CERTIFICATE_SHA256);
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

    private void recoverInterruptedSyncs() {
        Context context = getContext();
        File externalFiles = context == null ? null : context.getExternalFilesDir(null);
        if (externalFiles == null) return;
        File modRoot = new File(externalFiles, "Mod");
        File[] remnants = externalFiles.listFiles((directory, name) ->
                name.startsWith(".sgmm-staging-") || name.startsWith(".sgmm-backup-"));
        if (remnants == null) return;
        for (File remnant : remnants) {
            if (remnant.getName().startsWith(".sgmm-backup-")) {
                String cacheKey = remnant.getName().substring(".sgmm-backup-".length());
                File restored = isCacheKey(cacheKey)
                        ? new File(modRoot, managedDirectoryName(cacheKey)) : null;
                if (restored != null && !restored.exists()) remnant.renameTo(restored);
                else deleteRecursively(remnant);
            } else {
                deleteRecursively(remnant);
            }
        }
    }

    private static Bundle result(String code, String reason) {
        Bundle result = new Bundle();
        result.putString(KEY_RESULT_CODE, code);
        result.putString(KEY_RESULT_REASON, reason);
        return result;
    }

    private static String managedDirectoryName(String cacheKey) {
        return MANAGER_DIRECTORY_PREFIX + cacheKey;
    }

    private static String managerCacheKey(String directoryName) {
        if (!directoryName.startsWith(MANAGER_DIRECTORY_PREFIX)) return null;
        String cacheKey = directoryName.substring(MANAGER_DIRECTORY_PREFIX.length());
        return isCacheKey(cacheKey) ? cacheKey : null;
    }

    private static void deleteLegacyManagedDirectories(File modRoot, String cacheKey) {
        File[] children = modRoot.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (!child.isDirectory()) continue;
            if (child.getName().matches("\\d{6}--" + cacheKey)) deleteRecursively(child);
        }
    }

    private static boolean isCacheKey(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
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
        for (File entry : entries) {
            if (entry.isFile() && "info.json".equalsIgnoreCase(entry.getName())) ++matches;
        }
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

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) return;
        try {
            descriptor.close();
        } catch (IOException ignored) {
        }
    }

    private static boolean deleteRecursively(File root) {
        if (root.isDirectory()) {
            File[] children = root.listFiles();
            if (children == null) return false;
            for (File child : children) if (!deleteRecursively(child)) return false;
        }
        return root.delete();
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
