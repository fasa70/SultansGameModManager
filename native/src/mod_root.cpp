#include "modloader/mod_root.h"

#include <mutex>
#include <utility>

namespace modloader {
namespace {

std::mutex g_mod_root_mutex;
std::string g_mod_root;

}  // namespace

void SetModRoot(std::string root) {
    std::lock_guard<std::mutex> lock(g_mod_root_mutex);
    g_mod_root = std::move(root);
}

std::string GetModRoot() {
    std::lock_guard<std::mutex> lock(g_mod_root_mutex);
    return g_mod_root;
}

}  // namespace modloader
