#include "modloader/canonical_dictionary.h"

#include <utility>

namespace modloader {

CanonicalDictionaryTransaction::CanonicalDictionaryTransaction(DictionaryOperations operations)
    : operations_(std::move(operations)) {}

bool CanonicalDictionaryTransaction::Apply(const std::string& key, void* value) {
    if (result_ != TransactionResult::kOpen || !operations_.lookup || !operations_.store ||
        !operations_.remove || !operations_.verify || !operations_.verify_absent) {
        return false;
    }

    bool existed = false;
    void* previous_value = nullptr;
    if (!operations_.lookup(key, &existed, &previous_value)) {
        if (!journal_.empty()) {
            Rollback();
        }
        return false;
    }
    journal_.push_back({key, existed, previous_value});
    if (!operations_.store(key, value) || !operations_.verify(key, value)) {
        Rollback();
        return false;
    }
    return true;
}

TransactionResult CanonicalDictionaryTransaction::Commit() {
    if (result_ == TransactionResult::kOpen) {
        result_ = TransactionResult::kCommitted;
    }
    return result_;
}

TransactionResult CanonicalDictionaryTransaction::Rollback() {
    if (result_ == TransactionResult::kCommitted || result_ == TransactionResult::kRollbackFailed) {
        return result_;
    }

    bool restored = true;
    for (auto entry = journal_.rbegin(); entry != journal_.rend(); ++entry) {
        if (entry->existed) {
            restored = operations_.store(entry->key, entry->previous_value) &&
                operations_.verify(entry->key, entry->previous_value) && restored;
        } else {
            const bool already_absent = operations_.verify_absent(entry->key);
            restored = (already_absent ||
                        (operations_.remove(entry->key) &&
                         operations_.verify_absent(entry->key))) &&
                restored;
        }
    }
    result_ = restored ? TransactionResult::kRolledBack : TransactionResult::kRollbackFailed;
    return result_;
}

TransactionResult CanonicalDictionaryTransaction::result() const {
    return result_;
}

std::size_t CanonicalDictionaryTransaction::mutation_count() const {
    return journal_.size();
}

CanonicalIntDictionaryTransaction::CanonicalIntDictionaryTransaction(
    IntDictionaryOperations operations)
    : operations_(std::move(operations)) {}

bool CanonicalIntDictionaryTransaction::Apply(std::int32_t key, void* value) {
    if (result_ != TransactionResult::kOpen || !operations_.lookup || !operations_.store ||
        !operations_.remove || !operations_.verify || !operations_.verify_absent) {
        return false;
    }

    bool existed = false;
    void* previous_value = nullptr;
    if (!operations_.lookup(key, &existed, &previous_value)) {
        if (!journal_.empty()) {
            Rollback();
        }
        return false;
    }
    journal_.push_back({key, existed, previous_value});
    if (!operations_.store(key, value) || !operations_.verify(key, value)) {
        Rollback();
        return false;
    }
    return true;
}

TransactionResult CanonicalIntDictionaryTransaction::Commit() {
    if (result_ == TransactionResult::kOpen) {
        result_ = TransactionResult::kCommitted;
    }
    return result_;
}

TransactionResult CanonicalIntDictionaryTransaction::Rollback() {
    if (result_ == TransactionResult::kCommitted || result_ == TransactionResult::kRollbackFailed) {
        return result_;
    }

    bool restored = true;
    for (auto entry = journal_.rbegin(); entry != journal_.rend(); ++entry) {
        if (entry->existed) {
            restored = operations_.store(entry->key, entry->previous_value) &&
                operations_.verify(entry->key, entry->previous_value) && restored;
        } else {
            const bool already_absent = operations_.verify_absent(entry->key);
            restored = (already_absent ||
                        (operations_.remove(entry->key) &&
                         operations_.verify_absent(entry->key))) &&
                restored;
        }
    }
    result_ = restored ? TransactionResult::kRolledBack : TransactionResult::kRollbackFailed;
    return result_;
}

TransactionResult CanonicalIntDictionaryTransaction::result() const {
    return result_;
}

std::size_t CanonicalIntDictionaryTransaction::mutation_count() const {
    return journal_.size();
}

}  // namespace modloader
