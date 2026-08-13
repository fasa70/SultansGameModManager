#include "resource_loader.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <fstream>
#include <sstream>
#include <stdexcept>
#include <thread>

namespace sultan {

namespace fs = std::filesystem;

// ── 内部工具 ──

fs::path ResourceLoader::to_path(const std::string& s) {
    return fs::u8path(s);
}

std::string ResourceLoader::from_path(const fs::path& p) {
    return p.u8string();
}

std::string ResourceLoader::normalize(const std::string& path) {
    auto norm = to_path(path).lexically_normal();
    auto s = from_path(norm);
    std::replace(s.begin(), s.end(), '\\', '/');
    return s;
}

void ResourceLoader::strip_bom(std::string& text) {
    if (text.size() >= 3 &&
        static_cast<unsigned char>(text[0]) == 0xEF &&
        static_cast<unsigned char>(text[1]) == 0xBB &&
        static_cast<unsigned char>(text[2]) == 0xBF) {
        text.erase(0, 3);
    }
}

bool ResourceLoader::match_pattern(const std::string& filename,
                                   const std::string& pattern) {
    if (pattern == "*") return true;
    if (pattern.size() >= 2 && pattern[0] == '*' && pattern[1] == '.') {
        auto ext = pattern.substr(1);
        if (filename.size() < ext.size()) return false;
        auto file_ext = filename.substr(filename.size() - ext.size());
        std::string lower_file_ext, lower_ext;
        lower_file_ext.resize(file_ext.size());
        lower_ext.resize(ext.size());
        std::transform(file_ext.begin(), file_ext.end(),
                       lower_file_ext.begin(), ::tolower);
        std::transform(ext.begin(), ext.end(), lower_ext.begin(), ::tolower);
        return lower_file_ext == lower_ext;
    }
    return filename == pattern;
}

void ResourceLoader::ensure_parent_dir(const fs::path& p) {
    auto parent = p.parent_path();
    if (!parent.empty()) {
        fs::create_directories(parent);
    }
}

// ── 读取 ──

TextPtr ResourceLoader::read_text(const std::string& path) {
    auto key = normalize(path);
    auto fspath = to_path(path);

    // 乐观读：先查缓存获取已缓存的 mtime
    TextPtr cached_content;
    fs::file_time_type cached_mtime{};
    bool has_cache = false;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = cache_.find(key);
        if (it != cache_.end()) {
            cached_content = it->second.content;
            cached_mtime = it->second.mtime;
            has_cache = true;
        }
    }

    // 无锁 stat 检查 mtime
    auto current_mtime = fs::last_write_time(fspath);
    if (has_cache && current_mtime == cached_mtime) {
        return cached_content;
    }

    // 缓存未命中或 mtime 变化 → 无锁读磁盘
    std::ifstream ifs(fspath, std::ios::binary);
    if (!ifs) {
        throw std::runtime_error("File not found: " + path);
    }
    std::ostringstream oss;
    oss << ifs.rdbuf();
    auto text = oss.str();
    strip_bom(text);

    auto ptr = std::make_shared<const std::string>(std::move(text));

    // 更新缓存
    {
        std::lock_guard<std::mutex> lock(mutex_);
        cache_[key] = {ptr, current_mtime};
    }
    return ptr;
}

std::string ResourceLoader::read_bytes(const std::string& path) {
    auto fspath = to_path(path);
    std::ifstream ifs(fspath, std::ios::binary);
    if (!ifs) {
        throw std::runtime_error("File not found: " + path);
    }
    std::ostringstream oss;
    oss << ifs.rdbuf();
    return oss.str();
}

std::vector<TextPtr> ResourceLoader::batch_read_text(
    const std::vector<std::string>& paths, int num_threads) {
    if (paths.empty()) return {};

    int n = num_threads;
    if (n <= 0) {
        n = static_cast<int>(std::thread::hardware_concurrency());
        if (n <= 0) n = 4;
    }
    n = std::min(n, static_cast<int>(paths.size()));

    std::vector<TextPtr> results(paths.size());
    std::atomic<size_t> next_idx{0};
    std::vector<std::exception_ptr> errors(paths.size(), nullptr);

    auto worker = [&]() {
        while (true) {
            auto idx = next_idx.fetch_add(1);
            if (idx >= paths.size()) break;
            try {
                results[idx] = read_text(paths[idx]);
            } catch (...) {
                errors[idx] = std::current_exception();
            }
        }
    };

    std::vector<std::thread> threads;
    for (int i = 0; i < n; ++i) {
        threads.emplace_back(worker);
    }
    for (auto& t : threads) t.join();

    for (size_t i = 0; i < errors.size(); ++i) {
        if (errors[i]) std::rethrow_exception(errors[i]);
    }
    return results;
}

// ── 写入 ──

void ResourceLoader::write_text(const std::string& path,
                                const std::string& content) {
    auto fspath = to_path(path);
    ensure_parent_dir(fspath);

    std::ofstream ofs(fspath, std::ios::binary);
    if (!ofs) {
        throw std::runtime_error("Failed to write: " + path);
    }
    ofs.write(content.data(), static_cast<std::streamsize>(content.size()));
    ofs.close();

    // 写入后主动更新缓存
    auto key = normalize(path);
    auto mtime = fs::last_write_time(fspath);
    auto ptr = std::make_shared<const std::string>(content);
    {
        std::lock_guard<std::mutex> lock(mutex_);
        cache_[key] = {ptr, mtime};
    }
}

// ── 复制 ──

void ResourceLoader::copy_file(const std::string& src,
                               const std::string& dest) {
    auto dest_path = to_path(dest);
    ensure_parent_dir(dest_path);
    fs::copy_file(to_path(src), dest_path,
                  fs::copy_options::overwrite_existing);

    auto key = normalize(dest);
    std::lock_guard<std::mutex> lock(mutex_);
    cache_.erase(key);
}

void ResourceLoader::copy_tree(const std::string& src,
                               const std::string& dest) {
    fs::copy(to_path(src), to_path(dest),
             fs::copy_options::recursive |
                 fs::copy_options::overwrite_existing);

    std::lock_guard<std::mutex> lock(mutex_);
    cache_.clear();
}

// ── 删除 ──

void ResourceLoader::remove_file(const std::string& path) {
    auto fspath = to_path(path);
    if (!fs::remove(fspath)) {
        throw std::runtime_error("File not found: " + path);
    }
    auto key = normalize(path);
    std::lock_guard<std::mutex> lock(mutex_);
    cache_.erase(key);
}

void ResourceLoader::remove_tree(const std::string& path) {
    fs::remove_all(to_path(path));
    std::lock_guard<std::mutex> lock(mutex_);
    cache_.clear();
}

bool ResourceLoader::remove_empty_dir(const std::string& path) {
    auto fspath = to_path(path);
    if (!fs::is_directory(fspath) || !fs::is_empty(fspath)) {
        return false;
    }
    fs::remove(fspath);
    return true;
}

// ── 目录 ──

void ResourceLoader::mkdir(const std::string& path) {
    fs::create_directories(to_path(path));
}

bool ResourceLoader::exists(const std::string& path) const {
    return fs::exists(to_path(path));
}

bool ResourceLoader::is_file(const std::string& path) const {
    return fs::is_regular_file(to_path(path));
}

bool ResourceLoader::is_dir(const std::string& path) const {
    return fs::is_directory(to_path(path));
}

std::vector<std::string> ResourceLoader::list_dir(
    const std::string& path) const {
    std::vector<std::string> result;
    for (const auto& entry : fs::directory_iterator(to_path(path))) {
        result.push_back(from_path(entry.path()));
    }
    return result;
}

std::vector<std::string> ResourceLoader::rglob(
    const std::string& dir, const std::string& pattern) const {
    std::vector<std::string> result;
    for (const auto& entry :
         fs::recursive_directory_iterator(to_path(dir))) {
        if (!entry.is_regular_file()) continue;
        auto filename = from_path(entry.path().filename());
        if (match_pattern(filename, pattern)) {
            result.push_back(from_path(entry.path()));
        }
    }
    return result;
}

// ── 缓存 ──

void ResourceLoader::invalidate(const std::string& path) {
    auto key = normalize(path);
    std::lock_guard<std::mutex> lock(mutex_);
    cache_.erase(key);
}

void ResourceLoader::clear_cache() {
    std::lock_guard<std::mutex> lock(mutex_);
    cache_.clear();
}

size_t ResourceLoader::cache_size() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return cache_.size();
}

// ── 元信息 ──

double ResourceLoader::get_mtime(const std::string& path) const {
    auto ftime = fs::last_write_time(to_path(path));
    auto sctp = std::chrono::time_point_cast<std::chrono::system_clock::duration>(
        ftime - fs::file_time_type::clock::now() +
        std::chrono::system_clock::now());
    return std::chrono::duration<double>(sctp.time_since_epoch()).count();
}

// ── 单例 ──

ResourceLoader& resource_loader() {
    static ResourceLoader instance;
    return instance;
}

}  // namespace sultan
