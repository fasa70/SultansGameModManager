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
import android.util.JsonToken;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;

public final class ModStorageProvider extends ContentProvider {
    private static final String MANAGER_PACKAGE = "com.sultansgame.modmanager";
    private static final String MANAGER_DIRECTORY_PREFIX = "sgmm-";
    private static final String REVISION_ASSET = "modloader/revision";
    private static final String KEY_EXPECTED_REVISION = "expectedRevision";
    private static final String KEY_CACHE_KEY = "cacheKey";
    private static final String KEY_INPUT = "input";
    private static final String KEY_OUTPUT = "output";
    private static final String KEY_RESULT_CODE = "resultCode";
    private static final String KEY_RESULT_REASON = "resultReason";
    private static final String KEY_MOD_NAMES = "modNames";
    private static final String KEY_MANAGER_CACHE_KEYS = "managerCacheKeys";
    private static final String KEY_SAVE_USER = "saveUser";
    private static final String KEY_SAVE_FILE = "saveFile";
    private static final String KEY_SAVE_LENGTH = "saveLength";
    private static final String KEY_SAVE_USERS = "saveUsers";
    private static final String KEY_SAVE_FILES = "saveFiles";
    private static final String RESULT_OK = "ok";
    private static final String RESULT_UNAUTHORIZED = "unauthorized";
    private static final String RESULT_INCOMPATIBLE = "incompatible";
    private static final String RESULT_INVALID = "invalid";
    private static final String RESULT_FAILED = "failed";
    private static final String RESULT_VALIDATION_FAILED = "validationFailed";
    private static final String RESULT_COMMIT_FAILED = "commitFailed";
    private static final String RESULT_INSUFFICIENT_STORAGE = "insufficientStorage";
    private static final String RESULT_SAVE_NOT_FOUND = "saveNotFound";
    private static final String RESULT_SAVE_TOO_LARGE = "saveTooLarge";
    private static final int MAX_ENTRY_COUNT = 10_000;
    /**
     * Save content is streamed over a pipe, so the Binder transaction limit does
     * not apply. This bound only exists to keep a corrupt or hostile length
     * header from filling the game's storage; real saves are far below it.
     */
    private static final long MAX_SAVE_BYTES = 64L * 1024L * 1024L;
    private static final String SAVE_RELATIVE_PATH = "SultansGame" + File.separator + "SAVEDATA";
    private static final String SAVE_BACKUP_SUFFIX = ".sgmm-bak";

    @Override
    public boolean onCreate() {
        recoverInterruptedSyncs();
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        ParcelFileDescriptor input = extras == null ? null : extras.getParcelable(KEY_INPUT);
        ParcelFileDescriptor output = extras == null ? null : extras.getParcelable(KEY_OUTPUT);
        try {
            if (!hasCompatibleRevision(extras)) return result(RESULT_INCOMPATIBLE, "Mod 加载器版本不匹配，请重新修补游戏");
            if (!isPinnedManager()) return result(RESULT_UNAUTHORIZED, "调用方证书不受信任，请重新修补游戏");
            if ("listMods".equals(method)) return listMods();
            if ("syncMod".equals(method)) return syncMod(extras, input);
            if ("removeManagedMod".equals(method)) return removeManagedMod(extras);
            if ("listSaveUsers".equals(method)) return listSaveUsers();
            if ("listSaveFiles".equals(method)) return listSaveFiles(extras);
            if ("readSave".equals(method)) return readSave(extras, output);
            if ("writeSave".equals(method)) return writeSave(extras, input);
            return result(RESULT_INVALID, "不支持的调用方法");
        } finally {
            closeQuietly(input);
            closeQuietly(output);
        }
    }

    /**
     * 本 split 打包的 loader revision（单一事实来源：assets/modloader/revision，
     * 与管理器内嵌模板里的同一个 entry）。读取失败视为自身损坏，按不兼容处理。
     */
    private static volatile int cachedRevision = -1;

    private boolean hasCompatibleRevision(Bundle extras) {
        if (extras == null) return false;
        int expected = extras.getInt(KEY_EXPECTED_REVISION, -1);
        if (expected <= 0) return false;
        int revision = cachedRevision;
        if (revision <= 0) {
            Integer parsed = readRevisionAsset();
            if (parsed == null) return false;
            cachedRevision = revision = parsed;
        }
        return revision == expected;
    }

    private Integer readRevisionAsset() {
        Context context = getContext();
        if (context == null) return null;
        try (InputStream stream = context.getAssets().open(REVISION_ASSET)) {
            byte[] buffer = new byte[32];
            int filled = 0;
            while (filled < buffer.length) {
                int count = stream.read(buffer, filled, buffer.length - filled);
                if (count < 0) break;
                filled += count;
            }
            if (filled >= buffer.length && stream.read() >= 0) return null;
            String text = new String(buffer, 0, filled, java.nio.charset.StandardCharsets.US_ASCII).trim();
            if (!text.matches("[1-9][0-9]{0,8}")) return null;
            return Integer.parseInt(text);
        } catch (IOException error) {
            return null;
        }
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
            if (isStorageFailure(error)) return result(RESULT_INSUFFICIENT_STORAGE, "游戏存储空间不足，请释放空间后重试");
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
            if (isStorageFailure(error)) return result(RESULT_INSUFFICIENT_STORAGE, "游戏存储空间不足，请释放空间后重试");
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

    private File saveRoot() {
        Context context = getContext();
        if (context == null) return null;
        File external = context.getExternalFilesDir(null);
        if (external == null) {
            File[] dirs = context.getExternalFilesDirs(null);
            if (dirs == null || dirs.length == 0 || dirs[0] == null) return null;
            external = dirs[0];
        }
        File packageDir = external.getParentFile();
        if (packageDir == null) return null;
        return new File(packageDir, SAVE_RELATIVE_PATH);
    }

    private File userDir(String uid) {
        File root = saveRoot();
        if (root == null || uid == null || !uid.matches("\\d{1,32}")) return null;
        return new File(root, uid);
    }

    private File saveFile(String uid, String name) {
        if (!isSaveFileName(name)) return null;
        File dir = userDir(uid);
        return dir == null ? null : new File(dir, name);
    }

    private static boolean isSaveFileName(String name) {
        if (!isSafeRelativePath(name)) return false;
        int separator = name.lastIndexOf('/');
        String leaf = separator >= 0 ? name.substring(separator + 1) : name;
        String lower = leaf.toLowerCase(Locale.ROOT);
        if (leaf.startsWith(".") || !lower.endsWith(".json") || lower.endsWith(".bak.json")) return false;
        if (separator < 0) return true;
        if (!"USERARCHIVE".equals(name.substring(0, separator)) || !leaf.matches("\\d{3}\\.json")) return false;
        int slot = Integer.parseInt(leaf.substring(0, 3));
        return slot >= 0 && slot < 10;
    }

    private Bundle listSaveUsers() {
        File root = saveRoot();
        ArrayList<String> users = new ArrayList<>();
        if (root != null && root.isDirectory()) {
            File[] children = root.listFiles();
            if (children != null) {
                Arrays.sort(children, Comparator.comparing(File::getName));
                for (File child : children) {
                    if (child.isDirectory() && child.getName().matches("\\d{1,32}")) {
                        users.add(child.getName());
                    }
                }
            }
        }
        Bundle response = result(RESULT_OK, null);
        response.putStringArrayList(KEY_SAVE_USERS, users);
        return response;
    }

    private Bundle listSaveFiles(Bundle extras) {
        String uid = extras == null ? null : extras.getString(KEY_SAVE_USER);
        File dir = userDir(uid);
        ArrayList<String> files = new ArrayList<>();
        if (dir != null && dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                Arrays.sort(children, Comparator.comparing(File::getName));
                for (File child : children) {
                    if (child.isFile() && isSaveFileName(child.getName())) files.add(child.getName());
                }
            }
            // The loop above already accepts every top-level *.json, including
            // user_archive.json; only the USERARCHIVE/ slot files need a second pass.
            File archiveDir = new File(dir, "USERARCHIVE");
            File[] slots = archiveDir.listFiles();
            if (slots != null) {
                Arrays.sort(slots, Comparator.comparing(File::getName));
                for (File slot : slots) {
                    if (slot.isFile() && isSaveFileName("USERARCHIVE/" + slot.getName())) {
                        files.add("USERARCHIVE/" + slot.getName());
                    }
                }
            }
        }
        Bundle response = result(RESULT_OK, null);
        response.putStringArrayList(KEY_SAVE_FILES, files);
        return response;
    }

    /**
     * Streams the save file into the manager's pipe. The content never enters the
     * reply Bundle, so neither the Binder transaction limit nor its UTF-16
     * expansion applies; the reply carries only the byte count for verification.
     */
    private Bundle readSave(Bundle extras, ParcelFileDescriptor output) {
        String uid = extras == null ? null : extras.getString(KEY_SAVE_USER);
        String name = extras == null ? null : extras.getString(KEY_SAVE_FILE);
        File target = saveFile(uid, name);
        if (target == null) return result(RESULT_INVALID, "存档路径无效");
        if (output == null) return result(RESULT_INVALID, "缺少存档传输通道");
        if (!target.isFile()) return result(RESULT_SAVE_NOT_FOUND, "存档文件不存在");
        if (target.length() > MAX_SAVE_BYTES) return result(RESULT_SAVE_TOO_LARGE, "存档文件过大");
        try {
            long copied;
            // AutoCloseOutputStream owns the descriptor, the same ownership rule
            // syncMod follows; wrapping the raw FileDescriptor instead would let
            // the stream and the later closeQuietly() both close the same fd.
            try (FileInputStream source = new FileInputStream(target);
                    OutputStream sink = new ParcelFileDescriptor.AutoCloseOutputStream(output)) {
                copied = copyStream(source, sink);
            }
            Bundle response = result(RESULT_OK, null);
            response.putLong(KEY_SAVE_LENGTH, copied);
            return response;
        } catch (IOException error) {
            return result(RESULT_FAILED, error.getMessage() == null ? "读取存档失败" : error.getMessage());
        }
    }

    /**
     * Reads the new content from the manager's pipe into a temporary file, then
     * validates and commits it. Content is never held whole in memory and never
     * crosses the Binder transaction, so save size is bounded by storage rather
     * than by the IPC channel.
     */
    private synchronized Bundle writeSave(Bundle extras, ParcelFileDescriptor input) {
        String uid = extras == null ? null : extras.getString(KEY_SAVE_USER);
        String name = extras == null ? null : extras.getString(KEY_SAVE_FILE);
        File target = saveFile(uid, name);
        if (target == null) return result(RESULT_INVALID, "存档路径无效");
        if (input == null) return result(RESULT_INVALID, "缺少存档传输通道");
        File dir = target.getParentFile();
        if (dir == null) return result(RESULT_COMMIT_FAILED, "存档目录不可用");
        if (dir.exists() && !dir.isDirectory()) return result(RESULT_COMMIT_FAILED, "存档目录不可用");
        if (!dir.exists() && !dir.mkdirs()) return result(RESULT_COMMIT_FAILED, "无法创建存档目录");

        File backup = new File(dir, target.getName() + SAVE_BACKUP_SUFFIX);
        File temp = new File(dir, target.getName() + ".sgmm-tmp-" + UUID.randomUUID());
        try {
            long written;
            try (InputStream source = new ParcelFileDescriptor.AutoCloseInputStream(input);
                    FileOutputStream output = new FileOutputStream(temp)) {
                written = copyStream(source, output, MAX_SAVE_BYTES);
                output.getFD().sync();
            }
            if (written > MAX_SAVE_BYTES) {
                temp.delete();
                return result(RESULT_SAVE_TOO_LARGE, "存档内容过大");
            }
            if (!isPlausibleJson(temp)) {
                temp.delete();
                return result(RESULT_VALIDATION_FAILED, "存档内容不是有效 JSON");
            }
            if (backup.exists() && !backup.delete()) throw new IOException("无法清理旧备份");
            if (target.exists() && !target.renameTo(backup)) throw new IOException("无法备份现有存档");
            if (!temp.renameTo(target)) {
                if (backup.exists() && !target.exists()) backup.renameTo(target);
                throw new IOException("无法写入存档");
            }
            Bundle response = result(RESULT_OK, null);
            response.putLong(KEY_SAVE_LENGTH, written);
            return response;
        } catch (IOException error) {
            if (temp.exists()) temp.delete();
            if (isStorageFailure(error)) return result(RESULT_INSUFFICIENT_STORAGE, "游戏存储空间不足，请释放空间后重试");
            return result(RESULT_COMMIT_FAILED, error.getMessage() == null ? "写入存档失败" : error.getMessage());
        }
    }

    private static long copyStream(InputStream source, OutputStream sink) throws IOException {
        return copyStream(source, sink, Long.MAX_VALUE);
    }

    /**
     * Copies up to `limit + 1` bytes so the caller can tell "exactly at the
     * limit" from "over it" without ever buffering the whole stream.
     */
    private static long copyStream(InputStream source, OutputStream sink, long limit)
            throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        while (total <= limit) {
            int read = source.read(buffer);
            if (read < 0) break;
            sink.write(buffer, 0, read);
            total += read;
        }
        return total;
    }

    /**
     * Streams the staged file through a strict JSON parser. Reading from the file
     * rather than a byte array keeps validation memory-flat, so save size stays
     * bounded by storage alone.
     */
    private static boolean isPlausibleJson(File file) {
        android.util.JsonReader reader = null;
        try {
            reader = new android.util.JsonReader(new InputStreamReader(
                    new BufferedInputStream(new FileInputStream(file)),
                    java.nio.charset.StandardCharsets.UTF_8));
            reader.setLenient(false);
            reader.skipValue();
            return reader.peek() == JsonToken.END_DOCUMENT;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }
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
            if (!isSafeRelativePath(relativePath) || size < 0 ||
                    !expectedDigest.matches("[0-9a-f]{64}")) {
                throw new IOException("Mod 文件无效");
            }
            totalSize = Math.addExact(totalSize, size);
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

    private static boolean isStorageFailure(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && (message.contains("ENOSPC") || message.contains("No space left") ||
                    message.contains("EDQUOT") || message.contains("File too large"))) return true;
        }
        return false;
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
        recoverInterruptedSaves();
    }

    private void recoverInterruptedSaves() {
        File root = saveRoot();
        if (root == null || !root.isDirectory()) return;
        File[] users = root.listFiles(File::isDirectory);
        if (users == null) return;
        for (File user : users) {
            if (!user.getName().matches("\\d{1,32}")) continue;
            File[] children = user.listFiles();
            if (children == null) continue;
            for (File child : children) {
                String name = child.getName();
                if (name.contains(".sgmm-tmp-")) {
                    deleteRecursively(child);
                    continue;
                }
                if (name.endsWith(SAVE_BACKUP_SUFFIX)) {
                    File target = new File(user, name.substring(0, name.length() - SAVE_BACKUP_SUFFIX.length()));
                    if (target.exists()) deleteRecursively(child);
                    else child.renameTo(target);
                }
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
