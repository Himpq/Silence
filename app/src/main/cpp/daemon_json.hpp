#pragma once

#include <cctype>
#include <climits>
#include <map>
#include <stdexcept>
#include <string>
#include <vector>

namespace silence {
// The control protocol deliberately accepts integer numbers only. Parsing is
// bounded, rejects duplicate keys, and never searches for keys inside strings.
struct Json {
    enum Kind { Null, Boolean, Number, String, Array, Object } kind = Null;
    bool boolean = false;
    long long number = 0;
    std::string string;
    std::vector<Json> array;
    std::map<std::string, Json> object;
    Json() = default;
    Json(bool value) : kind(Boolean), boolean(value) {}
    Json(int value) : kind(Number), number(value) {}
    Json(long long value) : kind(Number), number(value) {}
    Json(const std::string& value) : kind(String), string(value) {}
    Json(const char* value) : Json(std::string(value)) {}
    static Json dict() { Json v; v.kind = Object; return v; }
    static Json list() { Json v; v.kind = Array; return v; }
    const Json& at(const std::string& key) const {
        if (kind != Object) throw std::runtime_error("object_required");
        auto it = object.find(key);
        if (it == object.end()) throw std::runtime_error("missing_field:" + key);
        return it->second;
    }
    Json& operator[](const std::string& key) {
        if (kind != Object) throw std::runtime_error("object_required");
        return object[key];
    }
    bool has(const std::string& key) const { return kind == Object && object.count(key); }
    std::string text() const {
        if (kind != String) throw std::runtime_error("string_required");
        return string;
    }
    long long integer() const {
        if (kind != Number) throw std::runtime_error("integer_required");
        return number;
    }
    bool flag() const {
        if (kind != Boolean) throw std::runtime_error("boolean_required");
        return boolean;
    }
    static std::string quote(const std::string& value) {
        std::string out = "\"";
        for (unsigned char c : value) {
            switch (c) {
                case '"': out += "\\\""; break;
                case '\\': out += "\\\\"; break;
                case '\n': out += "\\n"; break;
                case '\r': out += "\\r"; break;
                case '\t': out += "\\t"; break;
                case '\b': out += "\\b"; break;
                case '\f': out += "\\f"; break;
                default:
                    if (c < 32) throw std::runtime_error("invalid_control_character");
                    out += static_cast<char>(c);
            }
        }
        return out + '"';
    }
    std::string dump() const {
        switch (kind) {
            case Null: return "null";
            case Boolean: return boolean ? "true" : "false";
            case Number: return std::to_string(number);
            case String: return quote(string);
            case Array: {
                std::string out = "[";
                for (const auto& v : array) { if (out.size() > 1) out += ','; out += v.dump(); }
                return out + ']';
            }
            case Object: {
                std::string out = "{";
                for (const auto& v : object) {
                    if (out.size() > 1) out += ',';
                    out += quote(v.first) + ':' + v.second.dump();
                }
                return out + '}';
            }
        }
        throw std::runtime_error("invalid_json_kind");
    }
    static Json parse(const std::string& input);
};

class JsonParser {
    const std::string& s;
    size_t p = 0;
    size_t nodes = 0;
    void space() { while (p < s.size() && std::isspace(static_cast<unsigned char>(s[p]))) ++p; }
    char next() { if (p == s.size()) throw std::runtime_error("unexpected_end"); return s[p++]; }
    unsigned hex4() {
        unsigned value = 0;
        for (int i = 0; i < 4; ++i) {
            char c = next();
            int v = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
            if (v < 0) throw std::runtime_error("invalid_json_escape");
            value = value * 16 + v;
        }
        return value;
    }
    std::string str() {
        if (next() != '"') throw std::runtime_error("string_required");
        std::string out;
        for (;;) {
            unsigned char c = next();
            if (c == '"') return out;
            if (c < 32) throw std::runtime_error("invalid_control_character");
            if (c != '\\') { out += static_cast<char>(c); continue; }
            switch (next()) {
                case '"': out += '"'; break;
                case '\\': out += '\\'; break;
                case '/': out += '/'; break;
                case 'b': out += '\b'; break;
                case 'f': out += '\f'; break;
                case 'n': out += '\n'; break;
                case 'r': out += '\r'; break;
                case 't': out += '\t'; break;
                case 'u': {
                    unsigned cp = hex4();
                    if (cp >= 0xd800 && cp <= 0xdbff) {
                        if (next() != '\\' || next() != 'u') throw std::runtime_error("invalid_surrogate");
                        unsigned low = hex4();
                        if (low < 0xdc00 || low > 0xdfff) throw std::runtime_error("invalid_surrogate");
                        cp = 0x10000 + ((cp - 0xd800) << 10) + low - 0xdc00;
                    } else if (cp >= 0xdc00 && cp <= 0xdfff) throw std::runtime_error("invalid_surrogate");
                    if (cp < 0x80) out += static_cast<char>(cp);
                    else if (cp < 0x800) { out += static_cast<char>(0xc0 | (cp >> 6)); out += static_cast<char>(0x80 | (cp & 63)); }
                    else if (cp < 0x10000) { out += static_cast<char>(0xe0 | (cp >> 12)); out += static_cast<char>(0x80 | ((cp >> 6) & 63)); out += static_cast<char>(0x80 | (cp & 63)); }
                    else { out += static_cast<char>(0xf0 | (cp >> 18)); out += static_cast<char>(0x80 | ((cp >> 12) & 63)); out += static_cast<char>(0x80 | ((cp >> 6) & 63)); out += static_cast<char>(0x80 | (cp & 63)); }
                    break;
                }
                default: throw std::runtime_error("invalid_json_escape");
            }
        }
    }
    Json value(int depth) {
        if (depth > 24 || ++nodes > 30000) throw std::runtime_error("json_limit");
        space();
        if (p == s.size()) throw std::runtime_error("unexpected_end");
        if (s[p] == '"') return Json(str());
        if (s[p] == '{' || s[p] == '[') {
            bool object = next() == '{';
            char end = object ? '}' : ']';
            Json v = object ? Json::dict() : Json::list();
            space();
            if (p < s.size() && s[p] == end) { ++p; return v; }
            for (;;) {
                space();
                if (object) {
                    std::string key = str(); space();
                    if (next() != ':' || v.has(key)) throw std::runtime_error("invalid_object_key");
                    v[key] = value(depth + 1);
                } else v.array.push_back(value(depth + 1));
                space(); char c = next();
                if (c == end) return v;
                if (c != ',') throw std::runtime_error("separator_required");
            }
        }
        for (const auto& literal : {std::string("true"), std::string("false"), std::string("null")}) {
            if (s.compare(p, literal.size(), literal) == 0) {
                p += literal.size(); return literal == "null" ? Json() : Json(literal == "true");
            }
        }
        size_t start = p;
        if (s[p] == '-') ++p;
        if (p == s.size() || !std::isdigit(static_cast<unsigned char>(s[p]))) throw std::runtime_error("invalid_number");
        if (s[p] == '0') ++p;
        else while (p < s.size() && std::isdigit(static_cast<unsigned char>(s[p]))) ++p;
        size_t used = 0;
        long long n = std::stoll(s.substr(start, p - start), &used);
        if (used != p - start) throw std::runtime_error("invalid_number");
        return Json(n);
    }
public:
    explicit JsonParser(const std::string& input) : s(input) {}
    Json parse() {
        if (s.size() > 512 * 1024) throw std::runtime_error("request_too_large");
        Json v = value(0); space();
        if (p != s.size()) throw std::runtime_error("trailing_json");
        return v;
    }
};
inline Json Json::parse(const std::string& input) { return JsonParser(input).parse(); }
}
