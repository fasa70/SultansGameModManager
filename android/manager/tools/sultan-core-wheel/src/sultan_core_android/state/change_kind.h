#pragma once
#include <cstdint>

namespace sultan {

enum class ChangeKind : uint8_t {
    Origin   = 0,
    Added    = 1,
    Deleted  = 2,
    Changed  = 3,
    MultiMod = 4,
    Override = 8,
};

constexpr ChangeKind operator|(ChangeKind a, ChangeKind b) {
    return static_cast<ChangeKind>(
        static_cast<uint8_t>(a) | static_cast<uint8_t>(b));
}
constexpr ChangeKind operator&(ChangeKind a, ChangeKind b) {
    return static_cast<ChangeKind>(
        static_cast<uint8_t>(a) & static_cast<uint8_t>(b));
}
constexpr ChangeKind operator~(ChangeKind a) {
    return static_cast<ChangeKind>(~static_cast<uint8_t>(a));
}
constexpr ChangeKind& operator|=(ChangeKind& a, ChangeKind b) {
    a = a | b;
    return a;
}
constexpr ChangeKind& operator&=(ChangeKind& a, ChangeKind b) {
    a = a & b;
    return a;
}

constexpr ChangeKind base_kind(ChangeKind k) {
    return static_cast<ChangeKind>(static_cast<uint8_t>(k) & 0x03);
}
constexpr ChangeKind change_flags(ChangeKind k) {
    return static_cast<ChangeKind>(static_cast<uint8_t>(k) & ~0x03);
}

constexpr bool is_origin(ChangeKind k)    { return base_kind(k) == ChangeKind::Origin; }
constexpr bool is_added(ChangeKind k)     { return base_kind(k) == ChangeKind::Added; }
constexpr bool is_deleted(ChangeKind k)   { return base_kind(k) == ChangeKind::Deleted; }
constexpr bool is_changed(ChangeKind k)   { return base_kind(k) == ChangeKind::Changed; }
constexpr bool is_multi_mod(ChangeKind k) { return (static_cast<uint8_t>(k) & 4) != 0; }
constexpr bool is_override(ChangeKind k)  { return (static_cast<uint8_t>(k) & 8) != 0; }

enum class MergeMode : uint8_t {
    Normal,
    Smart,
    Replace,
    Adaptive,
};

}  // namespace sultan
