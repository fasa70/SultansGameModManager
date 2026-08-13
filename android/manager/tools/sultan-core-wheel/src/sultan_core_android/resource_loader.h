#pragma once
#include <cstdint>
#include <filesystem>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace sultan {

using TextPtr = std::shared_ptr<const std::string>;

class ResourceLoader {
public:
    // ── 读取 ──

    TextPtr read_text(const std::string& path);

    std::string read_bytes(const std::string& path);

    std::vector<TextPtr> batch_read_text(
        const std::vector<std::string>& paths, int num_threads = 0);

    // ── 写入 ──

    void write_text(const std::string& path, const std::string& content);

    // ── 复制 ──

    void copy_file(const std::string& src, const std::string& dest);
    void copy_tree(const std::string& src, const std::string& dest);

    // ── 删除 ──

    void remove_file(const std::string& path);
    void remove_tree(const std::string& path);
    bool remove_empty_dir(const std::string& path);

    // ── 目录 ──

    void mkdir(const std::string& path);
    bool exists(const std::string& path) const;
    bool is_file(const std::string& path) const;
    bool is_dir(const std::string& path) const;
    std::vector<std::string> list_dir(const std::string& path) const;
    std::vector<std::string> rglob(const std::string& dir,
                                   const std::string& pattern) const;

    // ── 缓存 ──

    void invalidate(const std::string& path);
    void clear_cache();
    size_t cache_size() const;

    // ── 元信息 ──

    double get_mtime(const std::string& path) const;

private:
    mutable std::mutex mutex_;

    struct CacheEntry {
        TextPtr content;
        std::filesystem::file_time_type mtime;
    };
    std::unordered_map<std::string, CacheEntry> cache_;

    static std::filesystem::path to_path(const std::string& s);
    static std::string from_path(const std::filesystem::path& p);
    static std::string normalize(const std::string& path);
    static void strip_bom(std::string& text);
    static bool match_pattern(const std::string& filename,
                              const std::string& pattern);
    void ensure_parent_dir(const std::filesystem::path& p);
};

ResourceLoader& resource_loader();

}  // namespace sultan
