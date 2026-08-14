#pragma once
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace sultan {

template <typename K, typename V>
class ordered_map {
    std::vector<std::pair<K, V>> data_;
    std::unordered_map<K, size_t> index_;

public:
    using iterator = typename std::vector<std::pair<K, V>>::iterator;
    using const_iterator = typename std::vector<std::pair<K, V>>::const_iterator;
    using value_type = std::pair<K, V>;

    ordered_map() = default;

    iterator begin() { return data_.begin(); }
    iterator end() { return data_.end(); }
    const_iterator begin() const { return data_.begin(); }
    const_iterator end() const { return data_.end(); }

    size_t size() const { return data_.size(); }
    bool empty() const { return data_.empty(); }

    iterator find(const K& key) {
        auto it = index_.find(key);
        if (it == index_.end()) return data_.end();
        return data_.begin() + static_cast<ptrdiff_t>(it->second);
    }

    const_iterator find(const K& key) const {
        auto it = index_.find(key);
        if (it == index_.end()) return data_.end();
        return data_.begin() + static_cast<ptrdiff_t>(it->second);
    }

    size_t count(const K& key) const {
        return index_.count(key);
    }

    V& operator[](const K& key) {
        auto it = index_.find(key);
        if (it != index_.end()) return data_[it->second].second;
        index_.emplace(key, data_.size());
        data_.emplace_back(key, V{});
        return data_.back().second;
    }

    template <typename M>
    void insert_or_assign(K key, M&& val) {
        auto it = index_.find(key);
        if (it != index_.end()) {
            data_[it->second].second = std::forward<M>(val);
        } else {
            index_.emplace(key, data_.size());
            data_.emplace_back(std::move(key), std::forward<M>(val));
        }
    }

    template <typename... Args>
    std::pair<iterator, bool> emplace(const K& key, Args&&... args) {
        auto it = index_.find(key);
        if (it != index_.end()) {
            return {data_.begin() + static_cast<ptrdiff_t>(it->second), false};
        }
        index_.emplace(key, data_.size());
        data_.emplace_back(std::piecewise_construct,
                           std::forward_as_tuple(key),
                           std::forward_as_tuple(std::forward<Args>(args)...));
        return {data_.begin() + static_cast<ptrdiff_t>(data_.size() - 1), true};
    }

    void reserve(size_t n) {
        data_.reserve(n);
        index_.reserve(n);
    }
};

}  // namespace sultan
