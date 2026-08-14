#include "similarity.h"
#include <rapidfuzz/distance/Indel.hpp>
#include <rapidfuzz/distance/Levenshtein.hpp>

namespace sultan {

int levenshtein_distance(const std::string& a, const std::string& b) {
    return static_cast<int>(
        rapidfuzz::levenshtein_distance(a, b));
}

double string_ratio(const std::string& a, const std::string& b) {
    if (a.empty() && b.empty()) return 1.0;
    return rapidfuzz::indel_normalized_similarity(a, b);
}

}  // namespace sultan
