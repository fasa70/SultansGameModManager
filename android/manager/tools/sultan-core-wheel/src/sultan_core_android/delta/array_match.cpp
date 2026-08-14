#include "array_match.h"
#include "json_doc.h"
#include "perf.h"
#include "similarity.h"
#include "state_node.h"
#include <algorithm>
#include <cstdlib>
#include <rapidfuzz/distance/Levenshtein.hpp>
#include <unordered_map>
#include <unordered_set>

namespace sultan {

using std::string;
using std::vector;
using std::pair;
using std::unordered_map;
using std::unordered_set;

static const vector<string> COMMON_MATCH_KEYS = {"guid", "id", "tag", "key"};
static const vector<string> CONTENT_FIELDS = {
    "result_text", "condition", "action", "result", "result_title"};

// ── JsonVal → 字符串（用于模糊匹配） ──

static void val_to_json(JsonVal v, string& out);

static void obj_to_json(JsonVal v, string& out) {
    struct KV { string key; string val; };
    vector<KV> entries;
    auto it = v.obj_iter();
    JsonVal::ObjEntry e;
    while (it.next(e)) {
        KV kv;
        kv.key = string(e.key, e.key_len);
        val_to_json(e.val, kv.val);
        entries.push_back(std::move(kv));
    }
    std::sort(entries.begin(), entries.end(),
              [](const KV& a, const KV& b) { return a.key < b.key; });
    out += '{';
    for (size_t i = 0; i < entries.size(); ++i) {
        if (i > 0) out += ',';
        out += '"';
        out += entries[i].key;
        out += "\":";
        out += entries[i].val;
    }
    out += '}';
}

static void arr_to_json(JsonVal v, string& out) {
    out += '[';
    auto it = v.arr_iter();
    JsonVal elem;
    bool first = true;
    while (it.next(elem)) {
        if (!first) out += ',';
        first = false;
        val_to_json(elem, out);
    }
    out += ']';
}

static void val_to_json(JsonVal v, string& out) {
    if (!v.valid()) { out += "null"; return; }
    switch (v.type()) {
        case JsonType::Null: out += "null"; break;
        case JsonType::Bool: out += v.get_bool() ? "true" : "false"; break;
        case JsonType::Int:  out += std::to_string(v.get_int()); break;
        case JsonType::Real: out += std::to_string(v.get_real()); break;
        case JsonType::Str:
            out += '"';
            out += v.get_str();
            out += '"';
            break;
        case JsonType::Obj: obj_to_json(v, out); break;
        case JsonType::Arr: arr_to_json(v, out); break;
    }
}

static string val_to_string(JsonVal v) {
    if (!v.valid()) return "";
    if (v.is_str()) return string(v.get_str());
    string result;
    val_to_json(v, result);
    return result;
}

static string field_val_to_string(JsonVal v) {
    if (!v.valid()) return "";
    if (v.is_str()) return string(v.get_str());
    string result;
    val_to_json(v, result);
    return result;
}

// ── element_similarity ──

double element_similarity(JsonVal a, JsonVal b) {
    if (!a.valid() || !b.valid()) return 0.0;
    if (a.type() != b.type()) return 0.0;

    switch (a.type()) {
        case JsonType::Null: return 1.0;
        case JsonType::Bool: return a.get_bool() == b.get_bool() ? 1.0 : 0.0;
        case JsonType::Int:  return a.get_int() == b.get_int() ? 1.0 : 0.0;
        case JsonType::Real: return a.get_real() == b.get_real() ? 1.0 : 0.0;
        case JsonType::Str:
            return (string(a.get_str()) == string(b.get_str())) ? 1.0 : 0.0;
        case JsonType::Obj:
        case JsonType::Arr: {
            string sa, sb;
            val_to_json(a, sa);
            val_to_json(b, sb);
            return string_ratio(sa, sb);
        }
    }
    return 0.0;
}

// ── 范围约束 ──

static pair<int, int> get_mod_range(
    int bi, int base_len, int mod_len,
    const unordered_map<int, int>& pair_map)
{
    int lo = 0;
    for (int prev = bi - 1; prev >= 0; --prev) {
        auto it = pair_map.find(prev);
        if (it != pair_map.end()) {
            lo = it->second + 1;
            break;
        }
    }
    int hi = mod_len;
    for (int next = bi + 1; next < base_len; ++next) {
        auto it = pair_map.find(next);
        if (it != pair_map.end()) {
            hi = it->second;
            break;
        }
    }
    return {lo, hi};
}

// ── 四阶段匹配 ──

ArrayMatching match_by_heuristic(JsonVal base_arr, JsonVal mod_arr) {
    return match_by_heuristic(collect_arr(base_arr), collect_arr(mod_arr));
}

ArrayMatching match_by_heuristic(const vector<JsonVal>& base, const vector<JsonVal>& mod) {
    SULTAN_PERF_SCOPE("match_heuristic");
    int base_len = static_cast<int>(base.size());
    int mod_len = static_cast<int>(mod.size());

    ArrayMatching result;

    if (base_len == 0) {
        for (int i = 0; i < mod_len; ++i)
            result.unmatched_mod.push_back(i);
        return result;
    }
    if (mod_len == 0) {
        for (int i = 0; i < base_len; ++i)
            result.unmatched_base.push_back(i);
        return result;
    }

    vector<pair<int, int>>& pairs = result.pairs;
    unordered_set<int> matched_mod;
    unordered_set<int> matched_base;
    bool has_fallback = false;

    vector<int> remaining_base;
    unordered_set<int> remaining_mod_set;
    unordered_map<int, int> pair_map;

    // ── 阶段 1：COMMON_MATCH_KEYS 精确匹配 ──
    {
    SULTAN_PERF_SCOPE("match_phase1_keys");
    int s_start = 0;

    for (int bi = 0; bi < base_len; ++bi) {
        if (!base[bi].is_obj()) continue;

        vector<int> s;
        for (int mi = s_start; mi < mod_len; ++mi) {
            if (matched_mod.count(mi) == 0) s.push_back(mi);
        }
        if (s.empty()) continue;

        bool matched_in_step1 = false;
        for (const auto& key : COMMON_MATCH_KEYS) {
            JsonVal base_field = base[bi].obj_get(key.c_str());
            if (!base_field.valid()) continue;

            string base_val = val_to_string(base_field);
            vector<int> hits;
            for (int mi : s) {
                if (!mod[mi].is_obj()) continue;
                JsonVal mod_field = mod[mi].obj_get(key.c_str());
                if (!mod_field.valid()) continue;
                if (val_to_string(mod_field) == base_val)
                    hits.push_back(mi);
            }

            if (hits.size() == 1) {
                pairs.push_back({bi, hits[0]});
                matched_mod.insert(hits[0]);
                matched_base.insert(bi);
                s_start = hits[0] + 1;
                matched_in_step1 = true;
                break;
            }
            if (hits.size() > 1) {
                s = hits;
            }
        }
        (void)matched_in_step1;
    }
    }

    // ── 阶段 2：内容字段模糊匹配 ──
    {
    SULTAN_PERF_SCOPE("match_phase2_levenshtein");
    for (int bi = 0; bi < base_len; ++bi) {
        if (matched_base.count(bi) == 0) remaining_base.push_back(bi);
    }
    for (int mi = 0; mi < mod_len; ++mi) {
        if (matched_mod.count(mi) == 0) remaining_mod_set.insert(mi);
    }
    for (auto& [bi, mi] : pairs) pair_map[bi] = mi;

    for (const auto& field : CONTENT_FIELDS) {
        // 预缓存 mod 元素的该字段序列化结果
        unordered_map<int, string> mod_field_cache;
        for (int mi : remaining_mod_set) {
            if (!mod[mi].is_obj()) continue;
            JsonVal mfield = mod[mi].obj_get(field.c_str());
            if (!mfield.valid()) continue;
            string s = field_val_to_string(mfield);
            if (!s.empty()) mod_field_cache[mi] = std::move(s);
        }

        bool changed = true;
        while (changed) {
            changed = false;
            unordered_map<int, vector<pair<int, int>>> fwd;
            unordered_map<int, vector<pair<int, int>>> rev;

            for (int bi : remaining_base) {
                if (matched_base.count(bi)) continue;
                if (!base[bi].is_obj()) continue;
                JsonVal bfield = base[bi].obj_get(field.c_str());
                if (!bfield.valid()) continue;
                string base_str = field_val_to_string(bfield);
                if (base_str.size() < 2) continue;

                rapidfuzz::CachedLevenshtein<char> scorer(base_str);
                auto [lo, hi] = get_mod_range(bi, base_len, mod_len, pair_map);

                for (int mi : remaining_mod_set) {
                    if (mi < lo || mi >= hi) continue;
                    auto cache_it = mod_field_cache.find(mi);
                    if (cache_it == mod_field_cache.end()) continue;
                    const string& mod_str = cache_it->second;

                    size_t max_len = std::max(base_str.size(), mod_str.size());
                    size_t cutoff = static_cast<size_t>(max_len * 0.33);
                    size_t dist = scorer.distance(mod_str, cutoff);
                    if (dist <= cutoff) {
                        fwd[bi].push_back({mi, static_cast<int>(dist)});
                        rev[mi].push_back({bi, static_cast<int>(dist)});
                    }
                }
            }

            while (!fwd.empty()) {
                int best_bi = -1, best_mi = -1, best_dist = INT32_MAX;

                for (auto& [bi, candidates] : fwd) {
                    if (candidates.empty()) continue;
                    auto it = std::min_element(candidates.begin(), candidates.end(),
                        [](const pair<int,int>& a, const pair<int,int>& b) {
                            return a.second < b.second;
                        });
                    int mi = it->first;
                    int dist = it->second;

                    auto rev_it = rev.find(mi);
                    if (rev_it == rev.end() || rev_it->second.empty()) continue;
                    auto best_for_mi = std::min_element(
                        rev_it->second.begin(), rev_it->second.end(),
                        [](const pair<int,int>& a, const pair<int,int>& b) {
                            return a.second < b.second;
                        });
                    if (best_for_mi->first == bi && dist < best_dist) {
                        best_bi = bi;
                        best_mi = mi;
                        best_dist = dist;
                    }
                }

                if (best_bi < 0) break;

                pairs.push_back({best_bi, best_mi});
                matched_mod.insert(best_mi);
                matched_base.insert(best_bi);
                pair_map[best_bi] = best_mi;
                remaining_mod_set.erase(best_mi);

                fwd.erase(best_bi);
                rev.erase(best_mi);
                for (auto& [_, cands] : fwd) {
                    cands.erase(
                        std::remove_if(cands.begin(), cands.end(),
                            [best_mi](const pair<int,int>& p) { return p.first == best_mi; }),
                        cands.end());
                }
                for (auto& [_, cands] : rev) {
                    cands.erase(
                        std::remove_if(cands.begin(), cands.end(),
                            [best_bi](const pair<int,int>& p) { return p.first == best_bi; }),
                        cands.end());
                }
                changed = true;
            }
        }
    }

    remaining_base.clear();
    for (int bi = 0; bi < base_len; ++bi) {
        if (matched_base.count(bi) == 0) remaining_base.push_back(bi);
    }
    }

    // ── 阶段 3：兜底相似度匹配 ──
    {
    SULTAN_PERF_SCOPE("match_phase3_similarity");
    if (!remaining_base.empty() && !remaining_mod_set.empty()) {
        bool changed = true;
        while (changed) {
            changed = false;
            unordered_map<int, vector<pair<int, double>>> fwd_sim;
            unordered_map<int, vector<pair<int, double>>> rev_sim;

            for (int bi : remaining_base) {
                if (matched_base.count(bi)) continue;
                auto [lo, hi] = get_mod_range(bi, base_len, mod_len, pair_map);
                for (int mi : remaining_mod_set) {
                    if (mi < lo || mi >= hi) continue;
                    double sim = element_similarity(base[bi], mod[mi]);
                    if (sim > 0.5) {
                        fwd_sim[bi].push_back({mi, sim});
                        rev_sim[mi].push_back({bi, sim});
                    }
                }
            }

            while (!fwd_sim.empty()) {
                int best_bi = -1, best_mi = -1;
                double best_sim = -1.0;

                for (auto& [bi, cands] : fwd_sim) {
                    if (cands.empty()) continue;
                    auto it = std::max_element(cands.begin(), cands.end(),
                        [](const pair<int,double>& a, const pair<int,double>& b) {
                            return a.second < b.second;
                        });
                    int mi = it->first;
                    double sim = it->second;

                    auto rev_it = rev_sim.find(mi);
                    if (rev_it == rev_sim.end() || rev_it->second.empty()) continue;
                    auto best_for_mi = std::max_element(
                        rev_it->second.begin(), rev_it->second.end(),
                        [](const pair<int,double>& a, const pair<int,double>& b) {
                            return a.second < b.second;
                        });
                    if (best_for_mi->first == bi && sim > best_sim) {
                        best_bi = bi;
                        best_mi = mi;
                        best_sim = sim;
                    }
                }

                if (best_bi < 0) break;

                pairs.push_back({best_bi, best_mi});
                matched_mod.insert(best_mi);
                matched_base.insert(best_bi);
                pair_map[best_bi] = best_mi;
                remaining_mod_set.erase(best_mi);

                fwd_sim.erase(best_bi);
                rev_sim.erase(best_mi);
                for (auto& [_, cs] : fwd_sim) {
                    cs.erase(
                        std::remove_if(cs.begin(), cs.end(),
                            [best_mi](const pair<int,double>& p) { return p.first == best_mi; }),
                        cs.end());
                }
                for (auto& [_, cs] : rev_sim) {
                    cs.erase(
                        std::remove_if(cs.begin(), cs.end(),
                            [best_bi](const pair<int,double>& p) { return p.first == best_bi; }),
                        cs.end());
                }
                changed = true;
                has_fallback = true;
            }
        }

        remaining_base.clear();
        for (int bi = 0; bi < base_len; ++bi) {
            if (matched_base.count(bi) == 0) remaining_base.push_back(bi);
        }
    }

    // 非 dict 元素兜底
    for (int bi : vector<int>(remaining_base)) {
        if (base[bi].is_obj()) continue;
        auto [lo, hi] = get_mod_range(bi, base_len, mod_len, pair_map);
        int best_mi_scalar = -1;
        double best_sim_scalar = -1.0;
        for (int mi : remaining_mod_set) {
            if (mi < lo || mi >= hi) continue;
            double sim = element_similarity(base[bi], mod[mi]);
            if (sim > best_sim_scalar ||
                (sim == best_sim_scalar && best_mi_scalar >= 0 &&
                 abs(mi - bi) < abs(best_mi_scalar - bi))) {
                best_sim_scalar = sim;
                best_mi_scalar = mi;
            }
        }
        if (best_mi_scalar >= 0) {
            pairs.push_back({bi, best_mi_scalar});
            matched_mod.insert(best_mi_scalar);
            matched_base.insert(bi);
            pair_map[bi] = best_mi_scalar;
            remaining_mod_set.erase(best_mi_scalar);
            has_fallback = true;
        }
    }
    }

    // 收集未匹配
    vector<int> unmatched_b, unmatched_m;
    for (int bi = 0; bi < base_len; ++bi)
        if (matched_base.count(bi) == 0) unmatched_b.push_back(bi);
    for (int mi = 0; mi < mod_len; ++mi)
        if (matched_mod.count(mi) == 0) unmatched_m.push_back(mi);

    // ── 阶段 4：未配对元素按位置间隙对应 ──
    if (!unmatched_b.empty() && !unmatched_m.empty()) {
        vector<pair<int, int>> sorted_pairs(pair_map.begin(), pair_map.end());
        std::sort(sorted_pairs.begin(), sorted_pairs.end());

        vector<pair<int, int>> boundaries;
        boundaries.push_back({-1, -1});
        for (auto& p : sorted_pairs) boundaries.push_back(p);
        boundaries.push_back({base_len, mod_len});

        unordered_set<int> ub_set(unmatched_b.begin(), unmatched_b.end());
        unordered_set<int> um_set(unmatched_m.begin(), unmatched_m.end());
        vector<pair<int, int>> new_pairs;

        for (size_t k = 0; k + 1 < boundaries.size(); ++k) {
            int bi_lo = boundaries[k].first;
            int mi_lo = boundaries[k].second;
            int bi_hi = boundaries[k + 1].first;
            int mi_hi = boundaries[k + 1].second;

            vector<int> slot_ub, slot_um;
            for (int b : unmatched_b)
                if (b > bi_lo && b < bi_hi) slot_ub.push_back(b);
            for (int m : unmatched_m)
                if (m > mi_lo && m < mi_hi) slot_um.push_back(m);

            int pair_count = static_cast<int>(
                std::min(slot_ub.size(), slot_um.size()));
            for (int i = 0; i < pair_count; ++i) {
                int b = slot_ub[i], m = slot_um[i];
                if (base[b].is_obj() && mod[m].is_obj()) {
                    double sim = element_similarity(base[b], mod[m]);
                    if (sim < 0.5) continue;
                }
                new_pairs.push_back({b, m});
                ub_set.erase(b);
                um_set.erase(m);
            }
        }

        for (auto& p : new_pairs) pairs.push_back(p);
        unmatched_b.clear();
        unmatched_m.clear();
        for (int bi = 0; bi < base_len; ++bi)
            if (matched_base.count(bi) == 0 && ub_set.count(bi)) unmatched_b.push_back(bi);
        for (int mi = 0; mi < mod_len; ++mi)
            if (matched_mod.count(mi) == 0 && um_set.count(mi)) unmatched_m.push_back(mi);

        if (!new_pairs.empty()) has_fallback = true;
    }

    result.unmatched_base = std::move(unmatched_b);
    result.unmatched_mod = std::move(unmatched_m);
    result.confidence = has_fallback ? 0.3 : 1.0;
    return result;
}

}  // namespace sultan
