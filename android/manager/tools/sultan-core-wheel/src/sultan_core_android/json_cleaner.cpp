#include "json_cleaner.h"
#include "json_doc.h"

#include <string>

namespace sultan {
namespace {

// ═══════════════════════════════════════════════════════════
// UTF-8 引号族匹配
// ═══════════════════════════════════════════════════════════

// " U+201C = E2 80 9C, " U+201D = E2 80 9D, ＂ U+FF02 = EF BC 82
static size_t matchDoubleQuoteLike(const char* s, size_t i, size_t n) {
    if (i >= n) return 0;
    if (s[i] == '"') return 1;
    if (i + 2 < n) {
        auto a = static_cast<unsigned char>(s[i]);
        auto b = static_cast<unsigned char>(s[i + 1]);
        auto c = static_cast<unsigned char>(s[i + 2]);
        if (a == 0xE2 && b == 0x80 && (c == 0x9C || c == 0x9D)) return 3;
        if (a == 0xEF && b == 0xBC && c == 0x82) return 3;
    }
    return 0;
}

static bool isAsciiDoubleQuote(const char* s, size_t i, size_t n) {
    return i < n && s[i] == '"';
}

// ' U+2018 = E2 80 98, ' U+2019 = E2 80 99, ` U+0060, ´ U+00B4 = C2 B4
static size_t matchSingleQuoteLike(const char* s, size_t i, size_t n) {
    if (i >= n) return 0;
    if (s[i] == '\'' || s[i] == '`') return 1;
    if (i + 1 < n) {
        auto a = static_cast<unsigned char>(s[i]);
        auto b = static_cast<unsigned char>(s[i + 1]);
        if (a == 0xC2 && b == 0xB4) return 2;
    }
    if (i + 2 < n) {
        auto a = static_cast<unsigned char>(s[i]);
        auto b = static_cast<unsigned char>(s[i + 1]);
        auto c = static_cast<unsigned char>(s[i + 2]);
        if (a == 0xE2 && b == 0x80 && (c == 0x98 || c == 0x99)) return 3;
    }
    return 0;
}

static bool isAsciiSingleQuote(const char* s, size_t i, size_t n) {
    return i < n && s[i] == '\'';
}

static size_t matchQuote(const char* s, size_t i, size_t n) {
    size_t dl = matchDoubleQuoteLike(s, i, n);
    if (dl > 0) return dl;
    return matchSingleQuoteLike(s, i, n);
}

enum class EndQuoteMode {
    AsciiDouble,
    AsciiSingle,
    DoubleQuoteLike,
    SingleQuoteLike
};

static EndQuoteMode determineEndQuoteMode(const char* s, size_t i, size_t n) {
    if (isAsciiDoubleQuote(s, i, n)) return EndQuoteMode::AsciiDouble;
    if (isAsciiSingleQuote(s, i, n)) return EndQuoteMode::AsciiSingle;
    if (matchSingleQuoteLike(s, i, n) > 0) return EndQuoteMode::SingleQuoteLike;
    return EndQuoteMode::DoubleQuoteLike;
}

static size_t matchEndQuote(EndQuoteMode mode, const char* s, size_t i, size_t n) {
    switch (mode) {
    case EndQuoteMode::AsciiDouble:
        return isAsciiDoubleQuote(s, i, n) ? 1 : 0;
    case EndQuoteMode::AsciiSingle:
        return isAsciiSingleQuote(s, i, n) ? 1 : 0;
    case EndQuoteMode::DoubleQuoteLike:
        return matchDoubleQuoteLike(s, i, n);
    case EndQuoteMode::SingleQuoteLike:
        return matchSingleQuoteLike(s, i, n);
    }
    return 0;
}

// ═══════════════════════════════════════════════════════════
// 字符分类
// ═══════════════════════════════════════════════════════════

static bool isWhitespace(char c) {
    return c == ' ' || c == '\n' || c == '\t' || c == '\r';
}

static size_t matchSpecialWhitespace(const char* s, size_t i, size_t n) {
    if (i >= n) return 0;
    if (i + 2 < n) {
        auto a = static_cast<unsigned char>(s[i]);
        auto b = static_cast<unsigned char>(s[i + 1]);
        auto c = static_cast<unsigned char>(s[i + 2]);
        if (a == 0xE3 && b == 0x80 && c == 0x80) return 3;
        if (a == 0xE2 && b == 0x80 && c >= 0x80 && c <= 0x8B) return 3;
        if (a == 0xE2 && b == 0x80 && (c == 0xA8 || c == 0xA9)) return 3;
        if (a == 0xEF && b == 0xBB && c == 0xBF) return 3;
        if (a == 0xE2 && b == 0x80 && c == 0xAF) return 3;
        if (a == 0xE2 && b == 0x81 && c == 0x9F) return 3;
    }
    if (i + 1 < n) {
        auto a = static_cast<unsigned char>(s[i]);
        auto b = static_cast<unsigned char>(s[i + 1]);
        if (a == 0xC2 && b == 0xA0) return 2;
        if (a == 0xC6 && b == 0x8E) return 2;
    }
    return 0;
}

static bool isHex(char c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
}

static bool isDigit(char c) { return c >= '0' && c <= '9'; }

static bool isDelimiter(char c) {
    return c == ',' || c == ':' || c == '[' || c == ']' || c == '/' ||
           c == '{' || c == '}' || c == '(' || c == ')' || c == '\n' || c == '+';
}

static bool isUnquotedStringDelimiter(char c) {
    return c == ',' || c == '[' || c == ']' || c == '/' || c == '{' ||
           c == '}' || c == '\n' || c == '+';
}

static bool isControlCharacter(char c) {
    return c == '\n' || c == '\r' || c == '\t' || c == '\b' || c == '\f';
}

static bool isStartOfValue(const char* s, size_t i, size_t n) {
    if (i >= n) return false;
    char c = s[i];
    if (c == '[' || c == '{') return true;
    if (c == '-') return true;
    if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_') return true;
    if (c >= '0' && c <= '9') return true;
    if (matchQuote(s, i, n) > 0) return true;
    return false;
}

static bool isFunctionNameCharStart(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '$';
}

static bool isFunctionNameChar(char c) {
    return isFunctionNameCharStart(c) || (c >= '0' && c <= '9');
}

// ═══════════════════════════════════════════════════════════
// 字符串操作
// ═══════════════════════════════════════════════════════════

static std::string insertBeforeLastWhitespace(const std::string& text, char ch) {
    size_t idx = text.size();
    if (idx == 0 || !isWhitespace(text[idx - 1])) {
        return text + ch;
    }
    while (idx > 0 && isWhitespace(text[idx - 1])) {
        idx--;
    }
    return text.substr(0, idx) + ch + text.substr(idx);
}

static std::string insertBeforeLastWhitespace(const std::string& text, const char* s) {
    size_t idx = text.size();
    if (idx == 0 || !isWhitespace(text[idx - 1])) {
        return text + s;
    }
    while (idx > 0 && isWhitespace(text[idx - 1])) {
        idx--;
    }
    return text.substr(0, idx) + s + text.substr(idx);
}

static std::string stripLastOccurrence(const std::string& text, char ch) {
    size_t pos = text.rfind(ch);
    if (pos == std::string::npos) return text;
    return text.substr(0, pos) + text.substr(pos + 1);
}

// ═══════════════════════════════════════════════════════════
// 转义字符映射
// ═══════════════════════════════════════════════════════════

static bool isEscapeChar(char c) {
    return c == '"' || c == '\\' || c == '/' || c == 'b' || c == 'f' ||
           c == 'n' || c == 'r' || c == 't';
}

static const char* controlCharEscape(char c) {
    switch (c) {
    case '\b': return "\\b";
    case '\f': return "\\f";
    case '\n': return "\\n";
    case '\r': return "\\r";
    case '\t': return "\\t";
    default: return nullptr;
    }
}

// ═══════════════════════════════════════════════════════════
// JsonRepairer
// ═══════════════════════════════════════════════════════════

class JsonRepairer {
    const char* s_;
    size_t n_;
    size_t i_ = 0;
    size_t line_ = 1;
    std::string output_;
    std::vector<RepairEntry> repairs_;

public:
    explicit JsonRepairer(const char* data, size_t len)
        : s_(data), n_(len) {
        output_.reserve(len + len / 8);
    }

    CleanResult repair() {
        const bool processed = parseValue();
        if (!processed) {
            throwUnexpectedEnd();
        }

        bool processedComma = parseCharacter(',');
        if (processedComma) {
            parseWhitespaceAndSkipComments();
        }

        if (processedComma) {
            output_ = stripLastOccurrence(output_, ',');
        }

        while (i_ < n_ && (s_[i_] == '}' || s_[i_] == ']')) {
            repairs_.push_back({line_, s_[i_] == '}' ? "去除多余 }" : "去除多余 ]"});
            advance();
            parseWhitespaceAndSkipComments();
        }

        if (i_ >= n_) {
            return {std::move(output_), std::move(repairs_)};
        }
        throwUnexpectedCharacter();
        return {}; // unreachable
    }

private:
    void advance(size_t count = 1) {
        for (size_t k = 0; k < count && i_ < n_; ++k) {
            if (s_[i_] == '\n') ++line_;
            ++i_;
        }
    }

    void skipTrailingQuoteBeforeNewline() {
        if (i_ >= n_) return;
        size_t ql = matchQuote(s_, i_, n_);
        if (ql == 0) return;
        size_t j = i_ + ql;
        while (j < n_ && (s_[j] == ' ' || s_[j] == '\t')) ++j;
        if (j >= n_ || s_[j] == '\n') {
            advance(ql);
            repairs_.push_back({line_, "去除多余引号"});
        }
    }

    bool looksLikeObjectKeyColon(size_t pos) const {
        if (pos >= n_ || s_[pos] != '"') return false;
        size_t j = pos + 1;
        while (j < n_ && s_[j] != '"' && s_[j] != '\n') ++j;
        if (j >= n_ || s_[j] != '"') return false;
        ++j;
        while (j < n_ && (s_[j] == ' ' || s_[j] == '\t')) ++j;
        return j < n_ && s_[j] == ':';
    }

    // ── parseValue ──
    bool parseValue() {
        parseWhitespaceAndSkipComments();
        bool processed = parseObject() || parseArray() || parseString() ||
                         parseNumber() || parseKeywords() ||
                         parseUnquotedString(false);
        parseWhitespaceAndSkipComments();
        return processed;
    }

    // ── parseWhitespaceAndSkipComments ──
    void parseWhitespaceAndSkipComments(bool skipNewline = true) {
        bool changed = parseWhitespace(skipNewline);
        do {
            changed = parseComment();
            if (changed) {
                changed = parseWhitespace(skipNewline);
            }
        } while (changed);
    }

    bool parseWhitespace(bool skipNewline) {
        std::string ws;
        while (i_ < n_) {
            char c = s_[i_];
            if (skipNewline ? isWhitespace(c) : (c == ' ' || c == '\t' || c == '\r')) {
                ws += c;
                advance();
            } else {
                size_t swl = matchSpecialWhitespace(s_, i_, n_);
                if (swl > 0) {
                    ws += ' ';
                    advance(swl);
                } else {
                    break;
                }
            }
        }
        if (!ws.empty()) {
            output_ += ws;
            return true;
        }
        return false;
    }

    bool parseComment() {
        if (i_ + 1 < n_ && s_[i_] == '/' && s_[i_ + 1] == '*') {
            while (i_ < n_ && !(s_[i_] == '*' && i_ + 1 < n_ && s_[i_ + 1] == '/')) {
                advance();
            }
            advance(2);
            return true;
        }
        if (i_ + 1 < n_ && s_[i_] == '/' && s_[i_ + 1] == '/') {
            while (i_ < n_ && s_[i_] != '\n' && s_[i_] != '\r') {
                advance();
            }
            return true;
        }
        return false;
    }

    // ── parseCharacter / skipCharacter ──
    bool parseCharacter(char expected) {
        if (i_ < n_ && s_[i_] == expected) {
            output_ += s_[i_];
            advance();
            return true;
        }
        return false;
    }

    bool skipCharacter(char expected) {
        if (i_ < n_ && s_[i_] == expected) {
            advance();
            return true;
        }
        return false;
    }

    // ── skipEllipsis ──
    void skipEllipsis() {
        parseWhitespaceAndSkipComments();
        if (i_ + 2 < n_ && s_[i_] == '.' && s_[i_ + 1] == '.' && s_[i_ + 2] == '.') {
            advance(3);
            parseWhitespaceAndSkipComments();
            skipCharacter(',');
        }
    }

    // ── parseObject ──
    bool parseObject() {
        if (i_ >= n_ || s_[i_] != '{') return false;
        output_ += '{';
        advance();
        parseWhitespaceAndSkipComments();

        if (skipCharacter(',')) {
            parseWhitespaceAndSkipComments();
        }

        bool initial = true;
        while (i_ < n_ && s_[i_] != '}') {
            bool processedComma;
            if (!initial) {
                processedComma = parseCharacter(',');
                if (!processedComma) {
                    repairs_.push_back({line_, "补充缺失逗号"});
                    output_ = insertBeforeLastWhitespace(output_, ',');
                }
                parseWhitespaceAndSkipComments();
            } else {
                processedComma = true;
                initial = false;
            }

            skipEllipsis();

            bool processedKey = parseString() || parseUnquotedString(true);
            if (!processedKey) {
                if (i_ < n_ && s_[i_] == ',') {
                    output_ = stripLastOccurrence(output_, ',');
                    continue;
                }
                if (i_ >= n_ || s_[i_] == '}' || s_[i_] == '{' ||
                    s_[i_] == ']' || s_[i_] == '[') {
                    output_ = stripLastOccurrence(output_, ',');
                } else {
                    throwObjectKeyExpected();
                }
                break;
            }

            parseWhitespaceAndSkipComments();

            bool processedColon = parseCharacter(':');
            bool truncatedText = i_ >= n_;
            if (!processedColon) {
                if (isStartOfValue(s_, i_, n_) || truncatedText) {
                    repairs_.push_back({line_, "补充缺失冒号"});
                    output_ = insertBeforeLastWhitespace(output_, ':');
                } else {
                    throwColonExpected();
                }
            }

            bool processedValue = parseValue();
            if (!processedValue) {
                if (processedColon || truncatedText) {
                    repairs_.push_back({line_, "补充缺失值 null"});
                    output_ += "null";
                } else {
                    throwColonExpected();
                }
            }
        }

        if (i_ < n_ && s_[i_] == '}') {
            output_ += '}';
            advance();
        } else {
            repairs_.push_back({line_, "补充缺失 }"});
            output_ = insertBeforeLastWhitespace(output_, '}');
        }
        return true;
    }

    // ── parseArray ──
    bool parseArray() {
        if (i_ >= n_ || s_[i_] != '[') return false;
        output_ += '[';
        advance();
        parseWhitespaceAndSkipComments();

        if (skipCharacter(',')) {
            parseWhitespaceAndSkipComments();
        }

        bool initial = true;
        while (i_ < n_ && s_[i_] != ']') {
            if (!initial) {
                bool processedComma = parseCharacter(',');
                if (!processedComma) {
                    repairs_.push_back({line_, "补充缺失逗号"});
                    output_ = insertBeforeLastWhitespace(output_, ',');
                }
            } else {
                initial = false;
            }

            skipEllipsis();

            bool processedValue = parseValue();
            if (!processedValue) {
                if (i_ < n_ && s_[i_] == ',') {
                    output_ = stripLastOccurrence(output_, ',');
                    continue;
                }
                output_ = stripLastOccurrence(output_, ',');
                break;
            }
        }

        if (i_ < n_ && s_[i_] == ']') {
            output_ += ']';
            advance();
        } else {
            repairs_.push_back({line_, "补充缺失 ]"});
            output_ = insertBeforeLastWhitespace(output_, ']');
        }
        return true;
    }

    // ── parseString ──
    bool parseString(bool stopAtDelimiter = false, size_t stopAtIndex = SIZE_MAX) {
        bool skipEscapeChars = false;
        if (i_ < n_ && s_[i_] == '\\') {
            advance();
            skipEscapeChars = true;
        }

        size_t ql = matchQuote(s_, i_, n_);
        if (ql == 0) {
            if (skipEscapeChars) { --i_; if (i_ < n_ && s_[i_] == '\n') --line_; }
            return false;
        }

        bool isNonAsciiQuote = (ql > 1 || (ql == 1 && s_[i_] != '"' && s_[i_] != '\''));
        if (!isNonAsciiQuote && (s_[i_] == '\'' || s_[i_] == '`')) {
            isNonAsciiQuote = true;
        }

        EndQuoteMode endQuoteMode = determineEndQuoteMode(s_, i_, n_);

        if (endQuoteMode != EndQuoteMode::AsciiDouble && !stopAtDelimiter) {
            repairs_.push_back({line_, "替换非标准引号"});
        }

        size_t iBefore = i_;
        size_t lineBefore = line_;
        size_t oBefore = output_.size();
        size_t rBefore = repairs_.size();
        std::string str = "\"";
        advance(ql);

        while (true) {
            if (i_ >= n_) {
                size_t iPrev = prevNonWhitespaceIndex(i_ - 1);
                if (!stopAtDelimiter && iPrev < n_ && isDelimiter(s_[iPrev])) {
                    i_ = iBefore;
                    line_ = lineBefore;
                    output_.resize(oBefore);
                    repairs_.resize(rBefore);
                    return parseString(true);
                }
                str = insertBeforeLastWhitespace(str, '"');
                output_ += str;
                return true;
            }

            if (stopAtIndex != SIZE_MAX && i_ == stopAtIndex) {
                str = insertBeforeLastWhitespace(str, '"');
                output_ += str;
                return true;
            }

            size_t eql = matchEndQuote(endQuoteMode, s_, i_, n_);
            if (eql > 0) {
                size_t iQuote = i_;
                size_t lineQuote = line_;
                size_t oQuote = str.size();
                str += '"';
                advance(eql);
                output_ += str;
                parseWhitespaceAndSkipComments(false);

                if (stopAtDelimiter || i_ >= n_ || isDelimiter(s_[i_]) ||
                    matchQuote(s_, i_, n_) > 0 || isDigit(s_[i_])) {
                    skipTrailingQuoteBeforeNewline();
                    return true;
                }

                size_t iPrevChar = prevNonWhitespaceIndex(iQuote - 1);
                char prevChar = (iPrevChar < n_) ? s_[iPrevChar] : '\0';
                if (prevChar == ',') {
                    i_ = iBefore;
                    line_ = lineBefore;
                    output_.resize(oBefore);
                    repairs_.resize(rBefore);
                    return parseString(false, iPrevChar);
                }

                if (isDelimiter(prevChar)) {
                    i_ = iBefore;
                    line_ = lineBefore;
                    output_.resize(oBefore);
                    repairs_.resize(rBefore);
                    return parseString(true);
                }

                if (looksLikeObjectKeyColon(iQuote)) {
                    size_t nlPos = SIZE_MAX;
                    for (size_t k = iQuote; k > iBefore + ql; --k) {
                        if (s_[k] == '\n') { nlPos = k; break; }
                    }
                    if (nlPos != SIZE_MAX) {
                        i_ = iBefore;
                        line_ = lineBefore;
                        output_.resize(oBefore);
                        repairs_.resize(rBefore);
                        return parseString(false, nlPos);
                    }
                }

                output_.resize(oBefore);
                i_ = iQuote;
                line_ = lineQuote;
                advance(eql);
                str = str.substr(0, oQuote) + "\\" + str.substr(oQuote);
            } else if (stopAtDelimiter && i_ < n_ && isUnquotedStringDelimiter(s_[i_])) {
                str = insertBeforeLastWhitespace(str, '"');
                output_ += str;
                return true;
            } else if (i_ < n_ && s_[i_] == '\\') {
                char nextCh = (i_ + 1 < n_) ? s_[i_ + 1] : '\0';
                if (isEscapeChar(nextCh)) {
                    str += s_[i_];
                    str += s_[i_ + 1];
                    advance(2);
                } else if (nextCh == 'u') {
                    size_t j = 2;
                    while (j < 6 && i_ + j < n_ && isHex(s_[i_ + j])) {
                        j++;
                    }
                    if (j == 6) {
                        str.append(s_ + i_, 6);
                        advance(6);
                    } else if (i_ + j >= n_) {
                        advance(n_ - i_);
                    } else {
                        throwInvalidUnicodeCharacter();
                    }
                } else if (nextCh == '\n') {
                    str += "\\n";
                    advance(2);
                } else {
                    str += nextCh;
                    advance(2);
                }
            } else if (i_ < n_) {
                char c = s_[i_];

                if (c == '"' && (i_ == 0 || s_[i_ - 1] != '\\')) {
                    str += "\\\"";
                    advance();
                } else if (isControlCharacter(c)) {
                    const char* esc = controlCharEscape(c);
                    if (esc) str += esc;
                    advance();
                } else {
                    str += c;
                    advance();
                }
            }

            if (skipEscapeChars) {
                if (i_ < n_ && s_[i_] == '\\') {
                    advance();
                }
            }
        }
        return false;
    }

    // ── parseNumber ──
    bool parseNumber() {
        size_t start = i_;
        size_t startLine = line_;

        if (i_ < n_ && s_[i_] == '-') {
            advance();
            if (atEndOfNumber()) {
                repairNumberEndingWithNumericSymbol(start);
                return true;
            }
            if (i_ >= n_ || !isDigit(s_[i_])) {
                i_ = start;
                line_ = startLine;
                return false;
            }
        }

        if (i_ >= n_ || !isDigit(s_[i_])) {
            i_ = start;
            line_ = startLine;
            return false;
        }

        while (i_ < n_ && isDigit(s_[i_])) {
            advance();
        }

        if (i_ < n_ && s_[i_] == '.') {
            advance();
            if (atEndOfNumber()) {
                repairNumberEndingWithNumericSymbol(start);
                return true;
            }
            if (i_ >= n_ || !isDigit(s_[i_])) {
                i_ = start;
                line_ = startLine;
                return false;
            }
            while (i_ < n_ && isDigit(s_[i_])) {
                advance();
            }
        }

        if (i_ < n_ && (s_[i_] == 'e' || s_[i_] == 'E')) {
            advance();
            if (i_ < n_ && (s_[i_] == '-' || s_[i_] == '+')) {
                advance();
            }
            if (atEndOfNumber()) {
                repairNumberEndingWithNumericSymbol(start);
                return true;
            }
            if (i_ >= n_ || !isDigit(s_[i_])) {
                i_ = start;
                line_ = startLine;
                return false;
            }
            while (i_ < n_ && isDigit(s_[i_])) {
                advance();
            }
        }

        if (!atEndOfNumber()) {
            i_ = start;
            line_ = startLine;
            return false;
        }

        if (i_ > start) {
            output_.append(s_ + start, i_ - start);
            return true;
        }
        return false;
    }

    // ── parseKeywords ──
    bool parseKeywords() {
        return parseKeyword("true", "true") ||
               parseKeyword("false", "false") ||
               parseKeyword("null", "null") ||
               parseKeyword("True", "true", "True->true") ||
               parseKeyword("False", "false", "False->false") ||
               parseKeyword("None", "null", "None->null");
    }

    bool parseKeyword(const char* name, const char* value,
                      const char* repairDesc = nullptr) {
        size_t len = 0;
        while (name[len]) len++;
        if (i_ + len > n_) return false;
        for (size_t k = 0; k < len; ++k) {
            if (s_[i_ + k] != name[k]) return false;
        }
        if (i_ + len < n_ && isFunctionNameChar(s_[i_ + len])) return false;
        if (repairDesc) {
            repairs_.push_back({line_, repairDesc});
        }
        output_ += value;
        advance(len);
        return true;
    }

    // ── parseUnquotedString ──
    bool parseUnquotedString(bool isKey) {
        size_t start = i_;
        size_t startLine = line_;

        while (i_ < n_ && !isUnquotedStringDelimiter(s_[i_]) &&
               matchQuote(s_, i_, n_) == 0 &&
               (!isKey || s_[i_] != ':')) {
            advance();
        }

        if (i_ > start) {
            while (i_ > start && isWhitespace(s_[i_ - 1])) {
                --i_;
                if (s_[i_] == '\n') --line_;
            }

            std::string symbol(s_ + start, i_ - start);

            if (symbol == "undefined") {
                output_ += "null";
            } else {
                output_ += '"';
                for (char c : symbol) {
                    if (c == '"') { output_ += "\\\""; }
                    else if (c == '\\') { output_ += "\\\\"; }
                    else if (isControlCharacter(c)) {
                        const char* esc = controlCharEscape(c);
                        if (esc) output_ += esc;
                    } else {
                        output_ += c;
                    }
                }
                output_ += '"';
            }

            if (i_ < n_ && s_[i_] == '"') {
                advance();
            }

            return true;
        }
        return false;
    }

    // ── 辅助函数 ──

    size_t prevNonWhitespaceIndex(size_t start) const {
        size_t prev = start;
        while (prev > 0 && prev < n_ && isWhitespace(s_[prev])) {
            prev--;
        }
        return prev;
    }

    bool atEndOfNumber() const {
        return i_ >= n_ || isDelimiter(s_[i_]) || isWhitespace(s_[i_]);
    }

    void repairNumberEndingWithNumericSymbol(size_t start) {
        output_.append(s_ + start, i_ - start);
        output_ += '0';
    }

    [[noreturn]] void throwUnexpectedEnd() const {
        throw JsonParseError(
            "unexpected end of json string",
            line_, "unexpected end of json string");
    }

    [[noreturn]] void throwUnexpectedCharacter() const {
        throw JsonParseError(
            std::string("unexpected character '") + s_[i_] +
            "' at position " + std::to_string(i_) +
            " (line: " + std::to_string(line_) + ")",
            line_,
            std::string("unexpected character '") + s_[i_] + "'");
    }

    [[noreturn]] void throwObjectKeyExpected() const {
        throw JsonParseError(
            "object key expected at position " + std::to_string(i_) +
            " (line: " + std::to_string(line_) + ")",
            line_, "object key expected");
    }

    [[noreturn]] void throwColonExpected() const {
        throw JsonParseError(
            "colon expected at position " + std::to_string(i_) +
            " (line: " + std::to_string(line_) + ")",
            line_, "colon expected");
    }

    [[noreturn]] void throwInvalidUnicodeCharacter() const {
        throw JsonParseError(
            "invalid unicode character at position " + std::to_string(i_) +
            " (line: " + std::to_string(line_) + ")",
            line_, "invalid unicode character");
    }
};

}  // anonymous namespace

CleanResult clean_text(const std::string& text) {
    if (text.empty()) return {text, {}};
    return JsonRepairer(text.data(), text.size()).repair();
}

}  // namespace sultan
