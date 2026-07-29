#pragma once

#include <cstdint>
#include <functional>
#include <string>
#include <vector>

namespace modloader {

struct DictionaryOperations {
    std::function<bool(const std::string&, bool*, void**)> lookup;
    std::function<bool(const std::string&, void*)> store;
    std::function<bool(const std::string&)> remove;
    std::function<bool(const std::string&, void*)> verify;
    std::function<bool(const std::string&)> verify_absent;
};

struct IntDictionaryOperations {
    std::function<bool(std::int32_t, bool*, void**)> lookup;
    std::function<bool(std::int32_t, void*)> store;
    std::function<bool(std::int32_t)> remove;
    std::function<bool(std::int32_t, void*)> verify;
    std::function<bool(std::int32_t)> verify_absent;
};

enum class TransactionResult {
    kOpen,
    kCommitted,
    kRolledBack,
    kRollbackFailed,
};

class CanonicalDictionaryTransaction {
  public:
    explicit CanonicalDictionaryTransaction(DictionaryOperations operations);

    bool Apply(const std::string& key, void* value);
    TransactionResult Commit();
    TransactionResult Rollback();
    TransactionResult result() const;
    std::size_t mutation_count() const;

  private:
    struct JournalEntry {
        std::string key;
        bool existed = false;
        void* previous_value = nullptr;
    };

    DictionaryOperations operations_;
    std::vector<JournalEntry> journal_;
    TransactionResult result_ = TransactionResult::kOpen;
};

class CanonicalIntDictionaryTransaction {
  public:
    explicit CanonicalIntDictionaryTransaction(IntDictionaryOperations operations);

    bool Apply(std::int32_t key, void* value);
    TransactionResult Commit();
    TransactionResult Rollback();
    TransactionResult result() const;
    std::size_t mutation_count() const;

  private:
    struct JournalEntry {
        std::int32_t key = 0;
        bool existed = false;
        void* previous_value = nullptr;
    };

    IntDictionaryOperations operations_;
    std::vector<JournalEntry> journal_;
    TransactionResult result_ = TransactionResult::kOpen;
};

}  // namespace modloader
