#include "daemon_control.hpp"
#include <condition_variable>
#include <algorithm>
#include <arpa/inet.h>
#include <atomic>
#include <chrono>
#include <cerrno>
#include <cctype>
#include <csignal>
#include <cmath>
#include <cstddef>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <dirent.h>
#include <fcntl.h>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <iterator>
#include <map>
#include <mutex>
#include <netinet/in.h>
#include <poll.h>
#include <sstream>
#include <string>
#include <sys/file.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <thread>
#include <unistd.h>
#include <vector>

namespace {

constexpr int kProtocolVersion = 8;
constexpr int kDefaultIntervalMs = 5000;
constexpr int kMinIntervalMs = 250;
constexpr int kMaxIntervalMs = 10000;
constexpr int kDefaultRecordIntervalMs = 15000;
constexpr int kMinRecordIntervalMs = 5000;
constexpr int kMaxRecordIntervalMs = 300000;

std::atomic<bool> g_running{true};
std::atomic<int> g_interval_ms{kDefaultIntervalMs};
std::atomic<long long> g_sampling_lease_until_ms{0};
std::atomic<int> g_sampling_generation{0};
std::mutex g_sampling_mutex;
std::condition_variable g_sampling_changed;

long long monotonic_ms() {
    timespec value{}; clock_gettime(CLOCK_BOOTTIME, &value);
    return value.tv_sec * 1000LL + value.tv_nsec / 1000000;
}
std::mutex g_snapshot_mutex;
std::mutex g_log_mutex;
std::string g_record_dir;
std::string g_record_config_path;
std::atomic<bool> g_recording_enabled{false};
std::atomic<int> g_record_interval_ms{kDefaultRecordIntervalMs};
std::atomic<long long> g_record_count{0};
std::atomic<long long> g_last_recorded_at_epoch_ms{0};
std::atomic<int> g_default_recording_mode{0};
std::mutex g_recording_modes_mutex;
std::map<std::string, int> g_package_recording_modes;
std::mutex g_profile_mutex;
std::string g_profile_id;
std::string g_profile_name;
std::string g_profile_path;
int g_profile_schema_version = 0;
long long g_profile_revision = 0;
long long g_profile_bytes = 0;
bool g_profile_readable = false;
bool g_control_requested = false;

struct CpuCore {
    int index = 0;
    int current_mhz = 0;
    int min_mhz = 0;
    int max_mhz = 0;
    float usage_percent = -1.0f;
    std::vector<int> available_mhz;
};

struct CpuStat {
    long long total = 0;
    long long idle = 0;
};

struct Power {
    bool available = false;
    float power_w = 0.0f;
    int battery_level = -1;
    float voltage_v = 0.0f;
    float temperature_c = 0.0f;
    bool has_temperature = false;
    std::string state = "unknown";
};

struct Snapshot {
    long long timestamp_epoch_ms = 0;
    std::vector<CpuCore> cores;
    Power power;
    std::string foreground_package;
};

Snapshot g_latest_snapshot;
std::map<int, CpuStat> g_previous_cpu_stats;

long long now_epoch_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::system_clock::now().time_since_epoch())
        .count();
}

std::string trim(std::string value) {
    const auto first = value.find_first_not_of(" \t\r\n");
    if (first == std::string::npos) {
        return {};
    }
    const auto last = value.find_last_not_of(" \t\r\n");
    return value.substr(first, last - first + 1);
}

std::string local_date_label(long long epoch_ms) {
    const std::time_t epoch_seconds = static_cast<std::time_t>(epoch_ms / 1000);
    std::tm local_time{};
    if (localtime_r(&epoch_seconds, &local_time) == nullptr) {
        return "1970-01-01";
    }
    char buffer[16]{};
    if (std::strftime(buffer, sizeof(buffer), "%Y-%m-%d", &local_time) == 0) {
        return "1970-01-01";
    }
    return buffer;
}

std::string local_time_label(long long epoch_ms) {
    const std::time_t epoch_seconds = static_cast<std::time_t>(epoch_ms / 1000);
    std::tm local_time{};
    if (localtime_r(&epoch_seconds, &local_time) == nullptr) {
        return "1970-01-01 00:00:00";
    }
    char buffer[32]{};
    if (std::strftime(buffer, sizeof(buffer), "%Y-%m-%d %H:%M:%S", &local_time) == 0) {
        return "1970-01-01 00:00:00";
    }
    return buffer;
}

std::string read_text(const std::string& path, size_t max_size = 4096) {
    std::ifstream input(path);
    if (!input) {
        return {};
    }
    std::string value;
    value.resize(max_size);
    input.read(value.data(), static_cast<std::streamsize>(max_size));
    value.resize(static_cast<size_t>(input.gcount()));
    return trim(value);
}

bool read_long(const std::string& path, long long& result) {
    const std::string value = read_text(path, 1024);
    if (value.empty()) {
        return false;
    }
    char* end = nullptr;
    errno = 0;
    const long long parsed = std::strtoll(value.c_str(), &end, 10);
    if (errno != 0 || end == value.c_str()) {
        return false;
    }
    result = parsed;
    return true;
}

std::vector<int> parse_numbers(const std::string& value) {
    std::vector<int> numbers;
    size_t index = 0;
    while (index < value.size()) {
        while (index < value.size() && !std::isdigit(static_cast<unsigned char>(value[index]))) {
            ++index;
        }
        if (index >= value.size()) {
            break;
        }
        size_t first_end = index + 1;
        while (first_end < value.size() && std::isdigit(static_cast<unsigned char>(value[first_end]))) {
            ++first_end;
        }
        try {
            const int first = std::stoi(value.substr(index, first_end - index));
            if (first_end + 1 < value.size() && value[first_end] == '-' &&
                std::isdigit(static_cast<unsigned char>(value[first_end + 1]))) {
                size_t second_end = first_end + 2;
                while (second_end < value.size() &&
                       std::isdigit(static_cast<unsigned char>(value[second_end]))) {
                    ++second_end;
                }
                const int second = std::stoi(value.substr(first_end + 1, second_end - first_end - 1));
                if (second >= first && second - first <= 256) {
                    for (int number = first; number <= second; ++number) {
                        numbers.push_back(number);
                    }
                } else {
                    numbers.push_back(first);
                    numbers.push_back(second);
                }
                index = second_end;
                continue;
            }
            numbers.push_back(first);
        } catch (...) {
            // Ignore malformed fragments from vendor sysfs files.
        }
        index = first_end;
    }
    return numbers;
}

std::vector<int> read_frequency_list(const std::string& path) {
    auto values = parse_numbers(read_text(path, 16384));
    for (int& value : values) {
        value /= 1000;
    }
    std::sort(values.begin(), values.end());
    values.erase(std::unique(values.begin(), values.end()), values.end());
    return values;
}

bool is_cpu_directory(const char* name) {
    if (name == nullptr || std::strncmp(name, "cpu", 3) != 0 || name[3] == '\0') {
        return false;
    }
    for (const char* cursor = name + 3; *cursor != '\0'; ++cursor) {
        if (!std::isdigit(static_cast<unsigned char>(*cursor))) {
            return false;
        }
    }
    return true;
}

std::vector<int> list_cpu_indexes() {
    std::vector<int> indexes;
    DIR* directory = opendir("/sys/devices/system/cpu");
    if (directory != nullptr) {
        while (const dirent* entry = readdir(directory)) {
            if (!is_cpu_directory(entry->d_name)) {
                continue;
            }
            try {
                indexes.push_back(std::stoi(entry->d_name + 3));
            } catch (...) {
                // Ignore a vendor directory with an invalid numeric suffix.
            }
        }
        closedir(directory);
    }
    std::sort(indexes.begin(), indexes.end());
    indexes.erase(std::unique(indexes.begin(), indexes.end()), indexes.end());
    return indexes;
}

bool read_frequency_file(const std::string& base, const std::vector<std::string>& names, int& mhz) {
    for (const std::string& name : names) {
        long long value = 0;
        if (read_long(base + "/" + name, value) && value > 0) {
            mhz = static_cast<int>(value / 1000);
            return true;
        }
    }
    return false;
}

bool read_cpu_values(const std::string& base, int& current, int& minimum, int& maximum,
                     std::vector<int>& available) {
    if (!read_frequency_file(base, {"scaling_cur_freq", "cpuinfo_cur_freq"}, current)) {
        return false;
    }
    read_frequency_file(base, {"scaling_min_freq", "cpuinfo_min_freq"}, minimum);
    read_frequency_file(base, {"scaling_max_freq", "cpuinfo_max_freq"}, maximum);
    if (minimum <= 0) {
        minimum = current;
    }
    if (maximum <= 0) {
        maximum = current;
    }
    available = read_frequency_list(base + "/scaling_available_frequencies");
    return true;
}

std::vector<CpuCore> read_cpu_cores() {
    std::vector<CpuCore> cores;
    for (const int index : list_cpu_indexes()) {
        const std::string base = "/sys/devices/system/cpu/cpu" + std::to_string(index) + "/cpufreq";
        CpuCore core;
        core.index = index;
        if (read_cpu_values(base, core.current_mhz, core.min_mhz, core.max_mhz, core.available_mhz)) {
            cores.push_back(std::move(core));
        }
    }
    if (!cores.empty()) {
        return cores;
    }

    DIR* directory = opendir("/sys/devices/system/cpu/cpufreq");
    if (directory == nullptr) {
        return cores;
    }
    while (const dirent* entry = readdir(directory)) {
        if (std::strncmp(entry->d_name, "policy", 6) != 0 || entry->d_name[6] == '\0') {
            continue;
        }
        const std::string base = "/sys/devices/system/cpu/cpufreq/" + std::string(entry->d_name);
        int current = 0;
        int minimum = 0;
        int maximum = 0;
        std::vector<int> available;
        if (!read_cpu_values(base, current, minimum, maximum, available)) {
            continue;
        }
        auto related = parse_numbers(read_text(base + "/related_cpus", 4096));
        if (related.empty()) {
            related = parse_numbers(read_text(base + "/affected_cpus", 4096));
        }
        if (related.empty()) {
            try {
                related.push_back(std::stoi(entry->d_name + 6));
            } catch (...) {
                continue;
            }
        }
        for (const int index : related) {
            cores.push_back(CpuCore{index, current, minimum, maximum, -1.0f, available});
        }
    }
    closedir(directory);
    std::sort(cores.begin(), cores.end(), [](const CpuCore& left, const CpuCore& right) {
        return left.index < right.index;
    });
    return cores;
}

std::map<int, float> read_cpu_usage_percent() {
    std::ifstream input("/proc/stat");
    if (!input) {
        return {};
    }
    std::map<int, CpuStat> current_stats;
    std::string line;
    while (std::getline(input, line)) {
        std::istringstream stream(line);
        std::string label;
        if (!(stream >> label) || label.rfind("cpu", 0) != 0 || label.size() <= 3) {
            continue;
        }
        const std::string index_text = label.substr(3);
        if (index_text.find_first_not_of("0123456789") != std::string::npos) {
            continue;
        }
        int index = -1;
        try {
            index = std::stoi(index_text);
        } catch (...) {
            continue;
        }
        std::vector<long long> fields;
        long long field = 0;
        while (stream >> field) {
            fields.push_back(field);
        }
        if (fields.size() < 4) {
            continue;
        }
        long long total = 0;
        for (size_t field_index = 0; field_index < fields.size() && field_index < 8; ++field_index) {
            total += std::max(0LL, fields[field_index]);
        }
        const long long idle = std::max(0LL, fields[3]) +
            (fields.size() > 4 ? std::max(0LL, fields[4]) : 0LL);
        current_stats[index] = CpuStat{total, idle};
    }

    std::map<int, float> usage;
    for (const auto& [index, current] : current_stats) {
        const auto previous = g_previous_cpu_stats.find(index);
        if (previous == g_previous_cpu_stats.end()) {
            continue;
        }
        const long long total_delta = current.total - previous->second.total;
        const long long idle_delta = current.idle - previous->second.idle;
        if (total_delta <= 0) {
            continue;
        }
        const float busy_delta = static_cast<float>(std::max(0LL, total_delta - idle_delta));
        usage[index] = std::clamp(busy_delta * 100.0f / static_cast<float>(total_delta), 0.0f, 100.0f);
    }
    g_previous_cpu_stats = std::move(current_stats);
    return usage;
}

Power read_power() {
    Power power;
    DIR* directory = opendir("/sys/class/power_supply");
    if (directory == nullptr) {
        return power;
    }
    while (const dirent* entry = readdir(directory)) {
        if (entry->d_name[0] == '.') {
            continue;
        }
        const std::string base = "/sys/class/power_supply/" + std::string(entry->d_name);
        const std::string type = read_text(base + "/type", 128);
        if (type != "Battery" && std::strncmp(entry->d_name, "BAT", 3) != 0) {
            continue;
        }
        long long current_ua = 0;
        long long voltage_uv = 0;
        const bool has_current = read_long(base + "/current_now", current_ua) ||
            read_long(base + "/current_avg", current_ua);
        const bool has_voltage = read_long(base + "/voltage_now", voltage_uv) ||
            read_long(base + "/voltage_avg", voltage_uv);
        if (!has_current || !has_voltage || voltage_uv <= 0) {
            continue;
        }
        power.available = true;
        const std::string state = read_text(base + "/status", 128);
        std::string normalized_state = state;
        std::transform(normalized_state.begin(), normalized_state.end(), normalized_state.begin(),
                       [](unsigned char character) { return static_cast<char>(std::tolower(character)); });
        if (normalized_state == "charging" || normalized_state == "full") {
            power.state = "charging";
        } else if (normalized_state == "discharging" || normalized_state == "not charging") {
            power.state = "discharging";
        } else {
            power.state = "idle";
        }
        const float raw_power = static_cast<float>(current_ua) / 1000000.0f *
            static_cast<float>(voltage_uv) / 1000000.0f;
        if (power.state == "charging") {
            power.power_w = std::abs(raw_power);
        } else if (power.state == "discharging") {
            power.power_w = -std::abs(raw_power);
        } else {
            power.power_w = raw_power;
        }
        long long capacity = 0;
        if (read_long(base + "/capacity", capacity)) {
            power.battery_level = std::clamp(static_cast<int>(capacity), 0, 100);
        }
        power.voltage_v = static_cast<float>(voltage_uv) / 1000000.0f;
        long long temperature = 0;
        if (read_long(base + "/temp", temperature)) {
            power.temperature_c = static_cast<float>(temperature) / 10.0f;
            power.has_temperature = true;
        }
        break;
    }
    closedir(directory);
    return power;
}

Snapshot collect_snapshot() {
    Snapshot snapshot;
    snapshot.timestamp_epoch_ms = now_epoch_ms();
    snapshot.cores = read_cpu_cores();
    const auto usage_percent = read_cpu_usage_percent();
    for (CpuCore& core : snapshot.cores) {
        const auto usage = usage_percent.find(core.index);
        if (usage != usage_percent.end()) {
            core.usage_percent = usage->second;
        }
    }
    snapshot.power = read_power();
    snapshot.foreground_package = silence::foreground_package();
    return snapshot;
}

std::string json_escape(const std::string& value) {
    std::ostringstream output;
    for (const char character : value) {
        switch (character) {
            case '\\': output << "\\\\"; break;
            case '"': output << "\\\""; break;
            case '\n': output << "\\n"; break;
            case '\r': output << "\\r"; break;
            case '\t': output << "\\t"; break;
            default: output << character; break;
        }
    }
    return output.str();
}

std::string nullable_string(const std::string& value) {
    return value.empty() ? "null" : "\"" + json_escape(value) + "\"";
}

std::string snapshot_json(const Snapshot& snapshot) {
    std::ostringstream output;
    output << "{\"type\":\"SNAPSHOT\",\"protocol\":" << kProtocolVersion
           << ",\"timestampEpochMs\":" << snapshot.timestamp_epoch_ms
           << ",\"foregroundPackage\":" << nullable_string(snapshot.foreground_package)
           << ",\"cpuCores\":[";
    for (size_t index = 0; index < snapshot.cores.size(); ++index) {
        if (index > 0) output << ',';
        const CpuCore& core = snapshot.cores[index];
        output << "{\"index\":" << core.index
               << ",\"currentMhz\":" << core.current_mhz
               << ",\"minMhz\":" << core.min_mhz
               << ",\"maxMhz\":" << core.max_mhz
               << ",\"usagePercent\":";
        if (core.usage_percent >= 0.0f) {
            output << std::fixed << std::setprecision(1) << core.usage_percent;
        } else {
            output << "null";
        }
        output << ",\"availableMhz\":[";
        for (size_t frequency = 0; frequency < core.available_mhz.size(); ++frequency) {
            if (frequency > 0) output << ',';
            output << core.available_mhz[frequency];
        }
        output << "]}";
    }
    output << "],\"power\":{";
    output << "\"available\":" << (snapshot.power.available ? "true" : "false")
           << ",\"powerW\":" << std::fixed << std::setprecision(3) << snapshot.power.power_w
           << ",\"batteryLevelPercent\":";
    if (snapshot.power.battery_level >= 0) output << snapshot.power.battery_level;
    else output << "null";
    output << ",\"voltageV\":" << snapshot.power.voltage_v
           << ",\"temperatureC\":";
    if (snapshot.power.has_temperature) output << snapshot.power.temperature_c;
    else output << "null";
    output << ",\"state\":\"" << json_escape(snapshot.power.state) << "\"}}";
    return output.str();
}

std::string status_json(long long started_at_epoch_ms) {
    std::string profile_id;
    std::string profile_name;
    std::string profile_path;
    int profile_schema_version = 0;
    long long profile_revision = 0;
    long long profile_bytes = 0;
    bool profile_readable = false;
    bool control_requested = false;
    {
        std::lock_guard<std::mutex> lock(g_profile_mutex);
        profile_id = g_profile_id;
        profile_name = g_profile_name;
        profile_path = g_profile_path;
        profile_schema_version = g_profile_schema_version;
        profile_revision = g_profile_revision;
        profile_bytes = g_profile_bytes;
        profile_readable = g_profile_readable;
        control_requested = g_control_requested;
    }
    std::ostringstream output;
    output << "{\"type\":\"STATUS\",\"protocol\":" << kProtocolVersion
           << ",\"pid\":" << getpid()
           << ",\"uid\":" << getuid()
           << ",\"startedAtEpochMs\":" << started_at_epoch_ms
           << ",\"recordingEnabled\":" << (g_recording_enabled.load() ? "true" : "false")
           << ",\"recordIntervalMs\":" << g_record_interval_ms.load()
           << ",\"recordCount\":" << g_record_count.load()
           << ",\"profileId\":\"" << json_escape(profile_id)
           << "\",\"profileName\":\"" << json_escape(profile_name)
           << "\",\"profilePath\":\"" << json_escape(profile_path)
           << "\",\"profileSchemaVersion\":" << profile_schema_version
           << ",\"profileRevision\":" << profile_revision
           << ",\"profileBytes\":" << profile_bytes
           << ",\"profileReadable\":" << (profile_readable ? "true" : "false")
           << ",\"controlRequested\":" << (control_requested ? "true" : "false")
           << ",\"controlEnabled\":false"
           << ",\"controlState\":\"configured_only\"}";
    return output.str();
}

std::string json_string_value(const std::string& text, const std::string& key) {
    const std::string marker = "\"" + key + "\"";
    size_t position = text.find(marker);
    if (position == std::string::npos) return {};
    position = text.find(':', position + marker.size());
    if (position == std::string::npos) return {};
    ++position;
    while (position < text.size() && std::isspace(static_cast<unsigned char>(text[position]))) ++position;
    if (position >= text.size() || text[position] != '"') return {};
    ++position;
    std::string value;
    bool escaped = false;
    for (; position < text.size(); ++position) {
        const char character = text[position];
        if (escaped) {
            value.push_back(character);
            escaped = false;
        } else if (character == '\\') {
            escaped = true;
        } else if (character == '"') {
            break;
        } else {
            value.push_back(character);
        }
    }
    return value;
}

int json_integer_value(const std::string& text, const std::string& key, int fallback) {
    const std::string marker = "\"" + key + "\"";
    size_t position = text.find(marker);
    if (position == std::string::npos) return fallback;
    position = text.find(':', position + marker.size());
    if (position == std::string::npos) return fallback;
    ++position;
    while (position < text.size() && std::isspace(static_cast<unsigned char>(text[position]))) ++position;
    size_t end = position;
    while (end < text.size() && (std::isdigit(static_cast<unsigned char>(text[end])) || text[end] == '-')) ++end;
    if (end == position) return fallback;
    try {
        return std::stoi(text.substr(position, end - position));
    } catch (...) {
        return fallback;
    }
}

long long json_long_value(const std::string& text, const std::string& key, long long fallback) {
    const std::string marker = "\"" + key + "\"";
    size_t position = text.find(marker);
    if (position == std::string::npos) return fallback;
    position = text.find(':', position + marker.size());
    if (position == std::string::npos) return fallback;
    ++position;
    while (position < text.size() && std::isspace(static_cast<unsigned char>(text[position]))) ++position;
    size_t end = position;
    while (end < text.size() && (std::isdigit(static_cast<unsigned char>(text[end])) || text[end] == '-')) ++end;
    if (end == position) return fallback;
    try {
        return std::stoll(text.substr(position, end - position));
    } catch (...) {
        return fallback;
    }
}

bool safe_profile_id(const std::string& value) {
    if (value.empty() || value.size() > 64) return false;
    for (size_t index = 0; index < value.size(); ++index) {
        const unsigned char character = static_cast<unsigned char>(value[index]);
        const bool valid = std::islower(character) || std::isdigit(character) || character == '.' ||
            character == '_' || character == '-';
        if (!valid || (index == 0 && !std::islower(character) && !std::isdigit(character))) return false;
    }
    return true;
}

bool read_profile_metadata(
    const std::string& path,
    const std::string& expected_id,
    std::string& profile_id,
    std::string& profile_name,
    int& schema_version,
    long long& revision,
    long long& bytes
) {
    std::ifstream input(path, std::ios::binary);
    if (!input) return false;
    input.seekg(0, std::ios::end);
    const std::streamoff size = input.tellg();
    if (size <= 0 || size > 2 * 1024 * 1024) return false;
    input.seekg(0, std::ios::beg);
    const std::string text(
        (std::istreambuf_iterator<char>(input)),
        std::istreambuf_iterator<char>()
    );
    profile_id = json_string_value(text, "id");
    profile_name = json_string_value(text, "name");
    schema_version = json_integer_value(text, "schemaVersion", 0);
    revision = json_long_value(text, "revision", 0);
    bytes = static_cast<long long>(text.size());
    const bool has_default = text.find("\"default\"") != std::string::npos;
    const bool has_packages = text.find("\"packages\"") != std::string::npos;
    const bool has_modes = text.find("\"modes\"") != std::string::npos;
    return safe_profile_id(profile_id) && profile_id == expected_id &&
        schema_version == 1 && !profile_name.empty() && has_default && has_packages && has_modes;
}

void write_log(const std::string& log_path, const std::string& message) {
    if (log_path.empty()) return;
    std::lock_guard<std::mutex> lock(g_log_mutex);
    std::ofstream output(log_path, std::ios::app);
    if (!output) return;
    output << now_epoch_ms() << " " << message << '\n';
}

bool read_recording_config(
    bool& enabled,
    int& interval_ms,
    int& default_mode,
    std::map<std::string, int>& package_modes,
    std::string& profile_id,
    std::string& profile_path,
    bool& control_requested
) {
    if (g_record_config_path.empty()) {
        return false;
    }
    std::ifstream input(g_record_config_path);
    if (!input) {
        return false;
    }
    bool found_enabled = false;
    bool found_interval = false;
    default_mode = 0;
    package_modes.clear();
    profile_id.clear();
    profile_path.clear();
    control_requested = false;
    std::string line;
    while (std::getline(input, line)) {
        const std::string trimmed = trim(line);
        if (trimmed.rfind("enabled=", 0) == 0) {
            const std::string value = trimmed.substr(std::string("enabled=").size());
            if (value == "1" || value == "true") {
                enabled = true;
                found_enabled = true;
            } else if (value == "0" || value == "false") {
                enabled = false;
                found_enabled = true;
            }
        } else if (trimmed.rfind("intervalMs=", 0) == 0) {
            try {
                interval_ms = std::clamp(
                    std::stoi(trimmed.substr(std::string("intervalMs=").size())),
                    kMinRecordIntervalMs,
                    kMaxRecordIntervalMs
                );
                found_interval = true;
            } catch (...) {
                // Keep the previous complete configuration if the file is being rewritten.
            }
        } else if (trimmed.rfind("defaultMode=", 0) == 0) {
            try {
                default_mode = std::clamp(
                    std::stoi(trimmed.substr(std::string("defaultMode=").size())),
                    0,
                    3
                );
            } catch (...) {
                default_mode = 0;
            }
        } else if (trimmed.rfind("packageMode=", 0) == 0) {
            const std::string value = trimmed.substr(std::string("packageMode=").size());
            const size_t separator = value.rfind('|');
            if (separator != std::string::npos && separator > 0) {
                try {
                    const int mode = std::clamp(std::stoi(value.substr(separator + 1)), 0, 3);
                    package_modes[value.substr(0, separator)] = mode;
                } catch (...) {
                    // Ignore one malformed package override and keep the complete file usable.
                }
            }
        } else if (trimmed.rfind("profileId=", 0) == 0) {
            profile_id = trim(trimmed.substr(std::string("profileId=").size()));
        } else if (trimmed.rfind("profilePath=", 0) == 0) {
            profile_path = trim(trimmed.substr(std::string("profilePath=").size()));
        } else if (trimmed.rfind("controlEnabled=", 0) == 0) {
            const std::string value = trim(trimmed.substr(std::string("controlEnabled=").size()));
            control_requested = value == "1" || value == "true";
        }
    }
    return found_enabled && found_interval;
}

void refresh_recording_config() {
    static long long previous_config_stamp = -1;
    static long long previous_profile_stamp = -1;
    auto stamp = [](const std::string& path) {
        struct stat st{};
        return stat(path.c_str(), &st) == 0 ? st.st_mtim.tv_sec * 1000000000LL + st.st_mtim.tv_nsec + st.st_size : 0LL;
    };
    const long long config_stamp = stamp(g_record_config_path);
    const long long profile_stamp = stamp(g_profile_path);
    if (config_stamp == previous_config_stamp && profile_stamp == previous_profile_stamp) return;
    previous_config_stamp = config_stamp;
    previous_profile_stamp = profile_stamp;
    bool enabled = false;
    int interval_ms = kDefaultRecordIntervalMs;
    int default_mode = 0;
    std::map<std::string, int> package_modes;
    std::string profile_id;
    std::string configured_profile_path;
    bool control_requested = false;
    if (!read_recording_config(
            enabled,
            interval_ms,
            default_mode,
            package_modes,
            profile_id,
            configured_profile_path,
            control_requested
        )) {
        return;
    }
    const bool previous_enabled = g_recording_enabled.exchange(enabled);
    const int previous_interval = g_record_interval_ms.exchange(interval_ms);
    if (enabled && (!previous_enabled || previous_interval != interval_ms)) {
        g_last_recorded_at_epoch_ms.store(0);
    }
    g_default_recording_mode.store(default_mode);
    {
        std::lock_guard<std::mutex> lock(g_recording_modes_mutex);
        g_package_recording_modes = std::move(package_modes);
    }
    std::string resolved_profile_path;
    std::string profile_name;
    int profile_schema_version = 0;
    long long profile_revision = 0;
    long long profile_bytes = 0;
    bool profile_readable = false;
    if (!g_record_dir.empty() && safe_profile_id(profile_id)) {
        resolved_profile_path = g_record_dir + "/performance_profiles/" + profile_id + ".json";
        // profilePath 由 App 下发仅用于诊断；daemon 只信任 recordDir 下的固定 profile 目录，
        // 防止 root daemon 被配置文件引导去读取任意路径。
        const bool path_matches = configured_profile_path.empty() ||
            configured_profile_path == resolved_profile_path;
        profile_readable = path_matches && read_profile_metadata(
            resolved_profile_path,
            profile_id,
            profile_id,
            profile_name,
            profile_schema_version,
            profile_revision,
            profile_bytes
        );
    }
    {
        std::lock_guard<std::mutex> lock(g_profile_mutex);
        g_profile_id = profile_id;
        g_profile_name = profile_name;
        g_profile_path = resolved_profile_path;
        g_profile_schema_version = profile_schema_version;
        g_profile_revision = profile_revision;
        g_profile_bytes = profile_bytes;
        g_profile_readable = profile_readable;
        g_control_requested = control_requested;
    }
}

bool acquire_record_lock(const std::string& lock_path, int client_uid) {
    constexpr int kAttempts = 240;
    for (int attempt = 0; attempt < kAttempts; ++attempt) {
        if (mkdir(lock_path.c_str(), 0700) == 0) {
            if (client_uid >= 0) {
                chmod(lock_path.c_str(), 0700);
                chown(lock_path.c_str(), static_cast<uid_t>(client_uid), static_cast<gid_t>(client_uid));
            }
            return true;
        }
        if (errno != EEXIST) {
            return false;
        }
        struct stat lock_stat{};
        if (stat(lock_path.c_str(), &lock_stat) == 0) {
            const std::time_t now_seconds = std::time(nullptr);
            if (now_seconds > lock_stat.st_mtime && now_seconds - lock_stat.st_mtime > 15) {
                rmdir(lock_path.c_str());
                continue;
            }
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    return false;
}

void release_record_lock(const std::string& lock_path) {
    rmdir(lock_path.c_str());
}

std::string csv_escape(const std::string& value) {
    if (value.find_first_of(",\"\n\r") == std::string::npos) {
        return value;
    }
    std::string escaped;
    escaped.reserve(value.size() + 2);
    escaped.push_back('\"');
    for (const char character : value) {
        if (character == '\"') {
            escaped.push_back('\"');
        }
        escaped.push_back(character);
    }
    escaped.push_back('\"');
    return escaped;
}

std::string join_csv(const std::vector<std::string>& fields) {
    std::ostringstream output;
    for (size_t index = 0; index < fields.size(); ++index) {
        if (index > 0) output << ',';
        output << fields[index];
    }
    return output.str();
}

bool append_performance_record(const Snapshot& snapshot, int client_uid) {
    if (g_record_dir.empty() || !snapshot.power.available || snapshot.cores.empty()) {
        return false;
    }
    const long long timestamp = snapshot.timestamp_epoch_ms > 0
        ? snapshot.timestamp_epoch_ms
        : now_epoch_ms();
    const long long previous_timestamp = g_last_recorded_at_epoch_ms.load();
    const int interval_ms = g_record_interval_ms.load();
    if (previous_timestamp > 0 && timestamp - previous_timestamp < interval_ms) {
        return false;
    }

    float group_sums[3] = {0.0f, 0.0f, 0.0f};
    int group_counts[3] = {0, 0, 0};
    for (const CpuCore& core : snapshot.cores) {
        const int group = core.index <= 3 ? 0 : (core.index <= 6 ? 1 : 2);
        group_sums[group] += static_cast<float>(core.current_mhz);
        group_counts[group]++;
    }
    const auto group_frequency = [&](int group) {
        return group_counts[group] > 0 ? group_sums[group] / group_counts[group] / 1000.0f : 0.0f;
    };

    std::ostringstream cpu1;
    std::ostringstream cpu2;
    std::ostringstream cpu3;
    std::ostringstream power;
    cpu1 << std::fixed << std::setprecision(2) << group_frequency(0);
    cpu2 << std::fixed << std::setprecision(2) << group_frequency(1);
    cpu3 << std::fixed << std::setprecision(2) << group_frequency(2);
    power << std::showpos << std::fixed << std::setprecision(2) << snapshot.power.power_w;

    std::vector<std::string> fields = {
        std::to_string(timestamp),
        "SAMPLE",
        cpu1.str(),
        cpu2.str(),
        cpu3.str(),
        csv_escape(snapshot.foreground_package),
        power.str(),
        std::to_string(g_default_recording_mode.load()),
        snapshot.power.battery_level >= 0 ? std::to_string(snapshot.power.battery_level) : "",
        "",
        "",
        "",
        "",
        ""
    };
    {
        std::lock_guard<std::mutex> lock(g_recording_modes_mutex);
        const auto mode = g_package_recording_modes.find(snapshot.foreground_package);
        if (mode != g_package_recording_modes.end()) {
            fields[7] = std::to_string(mode->second);
        }
    }
    const std::string date_label = local_date_label(timestamp);
    const std::string file_path = g_record_dir + "/silence_perf_" + date_label + ".csv";
    const std::string lock_path = g_record_dir + "/.silence_perf.lock";
    if (!acquire_record_lock(lock_path, client_uid)) {
        return false;
    }

    bool success = false;
    do {
        struct stat file_stat{};
        const bool has_file = stat(file_path.c_str(), &file_stat) == 0 && file_stat.st_size > 0;
        std::ofstream output(file_path, std::ios::app);
        if (!output) {
            break;
        }
        if (!has_file) {
            output << "# SILENCE_PERF_V2\n"
                   << "# DATE=" << date_label << "\n"
                   << "# START_EPOCH_MS=" << timestamp << "\n"
                   << "# START_TIME=" << local_time_label(timestamp) << "\n"
                   << "# SAMPLE=CPU1_GHZ,CPU2_GHZ,CPU3_GHZ,PACKAGE,POWER_W,GEAR,BATTERY\n"
                   << "---- POWER_RECORD_TIME=" << std::clamp(interval_ms / 1000, 5, 300) << "s ----\n";
        }
        output << join_csv(fields) << '\n';
        output.flush();
        success = static_cast<bool>(output);
        if (success && client_uid >= 0) {
            chmod(file_path.c_str(), 0660);
            chown(file_path.c_str(), static_cast<uid_t>(client_uid), static_cast<gid_t>(client_uid));
        }
    } while (false);
    release_record_lock(lock_path);
    if (success) {
        g_last_recorded_at_epoch_ms.store(timestamp);
        g_record_count.fetch_add(1);
    }
    return success;
}

void write_status_file(
    const std::string& status_path,
    const std::string& state,
    int error_number = 0,
    const std::string& detail = {}
) {
    if (status_path.empty()) return;
    std::ofstream output(status_path, std::ios::trunc);
    if (!output) return;
    output << "{\"state\":\"" << json_escape(state)
           << "\",\"pid\":" << getpid()
           << ",\"uid\":" << getuid()
           << ",\"errno\":" << error_number
           << ",\"detail\":\"" << json_escape(detail) << "\"}\n";
}

void prepare_log_file(const std::string& log_path, int client_uid) {
    if (log_path.empty() || client_uid < 0) return;
    const int fd = open(log_path.c_str(), O_CREAT | O_WRONLY | O_APPEND, 0660);
    if (fd < 0) return;
    fchmod(fd, 0660);
    fchown(fd, static_cast<uid_t>(client_uid), static_cast<gid_t>(client_uid));
    close(fd);
}

bool send_all(int fd, const std::string& value) {
    size_t offset = 0;
    while (offset < value.size()) {
        const ssize_t count = send(fd, value.data() + offset, value.size() - offset, MSG_NOSIGNAL);
        if (count <= 0) return false;
        offset += static_cast<size_t>(count);
    }
    return true;
}

long long thread_cpu_us() {
    timespec value{};
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &value);
    return value.tv_sec * 1000000LL + value.tv_nsec / 1000;
}

void handle_client(int client_fd, long long started_at_epoch_ms, int caller_uid) {
    timeval timeout{1, 500000};
    setsockopt(client_fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    setsockopt(client_fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
    std::string pending;
    char buffer[4096];
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(1500);
    while (g_running && std::chrono::steady_clock::now() < deadline) {
        const ssize_t count = recv(client_fd, buffer, sizeof(buffer), 0);
        if (count <= 0) return;
        pending.append(buffer, static_cast<size_t>(count));
        if (pending.size() > 512 * 1024) {
            send_all(client_fd, "{\"type\":\"ERROR\",\"code\":\"REQUEST_TOO_LARGE\"}\n");
            return;
        }
        const auto newline = pending.find('\n');
        if (newline == std::string::npos) continue;
        const auto started = std::chrono::steady_clock::now();
        const long long cpu_started = thread_cpu_us();
        std::string command = trim(pending.substr(0, newline));
        std::string metric_command = command;
        std::string response;
        try {
            if (!command.empty() && command.front() == '{') {
                auto request = silence::Json::parse(command);
                metric_command = request.at("command").text();
                response = silence::control_request(request, caller_uid).dump();
            } else if (command == "PING") {
                response = "{\"type\":\"PONG\",\"protocol\":" + std::to_string(kProtocolVersion) +
                    ",\"pid\":" + std::to_string(getpid()) + "}";
            } else if (command == "GET_STATUS") {
                response = status_json(started_at_epoch_ms);
                response.pop_back();
                response += ",\"samplingRequestedIntervalMs\":" + std::to_string(g_interval_ms.load()) +
                    ",\"samplingLeaseActive\":" + (monotonic_ms() < g_sampling_lease_until_ms.load() ? "true" : "false") +
                    ",\"control\":" + silence::control_metrics().dump() + "}";
            } else if (command == "GET_SNAPSHOT") {
                const auto now = monotonic_ms();
                const auto previous_lease = g_sampling_lease_until_ms.exchange(now + 10000);
                if (previous_lease < now) { g_sampling_generation.fetch_add(1); g_sampling_changed.notify_one(); }
                Snapshot copy;
                { std::lock_guard<std::mutex> lock(g_snapshot_mutex); copy = g_latest_snapshot; }
                copy.foreground_package = silence::foreground_package();
                response = snapshot_json(copy);
            } else if (command.rfind("SET_INTERVAL ", 0) == 0 && caller_uid != 1000) {
                size_t used = 0;
                auto argument = command.substr(13);
                const int requested = std::stoi(argument, &used);
                if (used != argument.size() || requested < kMinIntervalMs || requested > kMaxIntervalMs) throw std::runtime_error("INVALID_INTERVAL");
                g_interval_ms.store(requested);
                g_sampling_lease_until_ms.store(monotonic_ms() + 10000);
                g_sampling_generation.fetch_add(1); g_sampling_changed.notify_one();
                response = "{\"type\":\"INTERVAL\",\"intervalMs\":" + std::to_string(requested) + "}";
            } else if (command == "STOP" && caller_uid != 1000) {
                response = "{\"type\":\"STOPPING\"}";
                g_running = false;
                g_sampling_changed.notify_one();
            } else {
                response = "{\"type\":\"ERROR\",\"code\":\"UNKNOWN_COMMAND\"}";
            }
        } catch (const std::exception&) {
            metric_command = "INVALID_REQUEST";
            response = "{\"type\":\"ERROR\",\"code\":\"INVALID_REQUEST\"}";
        }
        if (metric_command.size() > 64) metric_command = "INVALID_REQUEST";
        silence::record_request_cost(metric_command,
            std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - started).count(),
            thread_cpu_us() - cpu_started);
        send_all(client_fd, response + "\n");
        return; // One bounded request per connection; no client can retain the server.
    }
}

bool detach_process() {
    const pid_t first_child = fork();
    if (first_child < 0) return false;
    if (first_child > 0) _exit(0);
    if (setsid() < 0) return false;
    const pid_t second_child = fork();
    if (second_child < 0) return false;
    if (second_child > 0) _exit(0);
    chdir("/");
    const int null_fd = open("/dev/null", O_RDWR);
    if (null_fd >= 0) {
        dup2(null_fd, STDIN_FILENO);
        dup2(null_fd, STDOUT_FILENO);
        dup2(null_fd, STDERR_FILENO);
        if (null_fd > STDERR_FILENO) close(null_fd);
    }
    return true;
}

void signal_handler(int) {
    g_running = false;
}

int parse_integer_argument(int argc, char** argv, const std::string& prefix, int fallback) {
    for (int index = 1; index < argc; ++index) {
        const std::string argument(argv[index]);
        if (argument.rfind(prefix, 0) != 0) continue;
        try {
            return std::stoi(argument.substr(prefix.size()));
        } catch (...) {
            return fallback;
        }
    }
    return fallback;
}

std::string parse_string_argument(int argc, char** argv, const std::string& prefix) {
    for (int index = 1; index < argc; ++index) {
        const std::string argument(argv[index]);
        if (argument.rfind(prefix, 0) == 0) {
            return argument.substr(prefix.size());
        }
    }
    return {};
}

}  // namespace

int main(int argc, char** argv) {
    const std::string socket_path = parse_string_argument(argc, argv, "--socket=");
    const int tcp_port = parse_integer_argument(argc, argv, "--tcp-port=", -1);
    const std::string log_path = parse_string_argument(argc, argv, "--log=");
    const std::string status_path = parse_string_argument(argc, argv, "--status=");
    g_record_dir = parse_string_argument(argc, argv, "--record-dir=");
    g_record_config_path = parse_string_argument(argc, argv, "--record-config=");
    const int client_uid = parse_integer_argument(
        argc,
        argv,
        "--client-uid=",
        -1
    );
    const int interval_ms = std::clamp(
        parse_integer_argument(argc, argv, "--interval-ms=", kDefaultIntervalMs),
        kMinIntervalMs,
        kMaxIntervalMs
    );
    g_interval_ms.store(interval_ms);
    bool foreground = false;
    for (int index = 1; index < argc; ++index) {
        foreground = foreground || std::string(argv[index]) == "--foreground";
    }
    const bool tcp_socket = tcp_port >= 0;
    const bool abstract_socket = !tcp_socket && !socket_path.empty() && socket_path.front() == '@';
    const bool filesystem_socket = !tcp_socket && !abstract_socket;
    const std::string socket_name = abstract_socket ? socket_path.substr(1) : socket_path;
    sockaddr_un path_probe{};
    const size_t max_socket_name_length = sizeof(path_probe.sun_path) - (abstract_socket ? 1 : 0);
    if ((tcp_socket && (tcp_port <= 0 || tcp_port > 65535)) ||
        (!tcp_socket && (socket_name.empty() || socket_name.size() >= max_socket_name_length))) {
        write_status_file(status_path, "invalid_socket", EINVAL, "socket name is empty or too long");
        return 2;
    }
    if (!foreground && !detach_process()) {
        write_status_file(status_path, "detach_failed", errno, "unable to detach process");
        return 3;
    }

    std::signal(SIGTERM, signal_handler);
    std::signal(SIGINT, signal_handler);

    const std::string lock_path = tcp_socket
        ? "/data/local/tmp/silence-daemon-" + std::to_string(client_uid) + ".tcp.lock"
        : abstract_socket
            ? "/data/local/tmp/" + socket_name + ".lock"
            : socket_path + ".lock";
    const int lock_fd = open(lock_path.c_str(), O_CREAT | O_RDWR, 0600);
    if (lock_fd < 0 || flock(lock_fd, LOCK_EX | LOCK_NB) != 0) {
        const int error_number = errno;
        write_status_file(status_path, "already_running", error_number, "daemon lock is held");
        if (lock_fd >= 0) close(lock_fd);
        return 0;
    }

    if (filesystem_socket) {
        unlink(socket_path.c_str());
    }
    const int server_fd = socket(tcp_socket ? AF_INET : AF_UNIX, SOCK_STREAM, 0);
    if (server_fd < 0) {
        const int error_number = errno;
        write_log(log_path, "socket create failed errno=" + std::to_string(error_number));
        write_status_file(status_path, "socket_create_failed", error_number, "socket() failed");
        close(lock_fd);
        return 4;
    }
    sockaddr_storage address{};
    socklen_t address_length = 0;
    if (tcp_socket) {
        auto* tcp_address = reinterpret_cast<sockaddr_in*>(&address);
        tcp_address->sin_family = AF_INET;
        tcp_address->sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        tcp_address->sin_port = htons(static_cast<uint16_t>(tcp_port));
        const int reuse_address = 1;
        setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &reuse_address, sizeof(reuse_address));
        address_length = sizeof(sockaddr_in);
    } else {
        auto* unix_address = reinterpret_cast<sockaddr_un*>(&address);
        unix_address->sun_family = AF_UNIX;
        if (abstract_socket) {
            std::memcpy(unix_address->sun_path + 1, socket_name.data(), socket_name.size());
            address_length = static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + 1 + socket_name.size());
        } else {
            std::strncpy(unix_address->sun_path, socket_name.c_str(), sizeof(unix_address->sun_path) - 1);
            address_length = sizeof(sockaddr_un);
        }
    }
    if (bind(server_fd, reinterpret_cast<sockaddr*>(&address), address_length) != 0) {
        const int error_number = errno;
        write_log(log_path, "socket bind failed errno=" + std::to_string(error_number));
        write_status_file(status_path, "socket_bind_failed", error_number, "bind() failed");
        close(server_fd);
        close(lock_fd);
        if (filesystem_socket) unlink(socket_path.c_str());
        return 5;
    }
    if (listen(server_fd, 4) != 0) {
        const int error_number = errno;
        write_log(log_path, "socket listen failed errno=" + std::to_string(error_number));
        write_status_file(status_path, "socket_listen_failed", error_number, "listen() failed");
        close(server_fd);
        close(lock_fd);
        if (filesystem_socket) unlink(socket_path.c_str());
        return 6;
    }
    if (filesystem_socket) {
        chmod(socket_path.c_str(), 0660);
    }
    if (client_uid >= 0 && filesystem_socket) {
        chown(socket_path.c_str(), static_cast<uid_t>(client_uid), static_cast<gid_t>(client_uid));
    }
    if (client_uid >= 0) prepare_log_file(log_path, client_uid);
    const long long started_at_epoch_ms = now_epoch_ms();
    try { silence::initialize_control(client_uid, log_path); }
    catch (const std::exception& e) {
        write_log(log_path, std::string("control initialization failed: ") + e.what());
        close(server_fd); close(lock_fd); return 7;
    }
    refresh_recording_config();
    write_status_file(
        status_path,
        "running",
        0,
        tcp_socket ? "loopback tcp socket" : (abstract_socket ? "abstract socket" : "filesystem socket")
    );
    write_log(log_path, "started pid=" + std::to_string(getpid()) +
        " uid=" + std::to_string(getuid()) +
        " intervalMs=" + std::to_string(interval_ms) +
        " recordDir=" + g_record_dir +
        " recordConfig=" + g_record_config_path);

    std::thread sampler([&]() {
        while (g_running) {
            refresh_recording_config();
            const auto sample_started = std::chrono::steady_clock::now();
            const auto sample_cpu_started = thread_cpu_us();
            const Snapshot snapshot = collect_snapshot();
            silence::record_request_cost("SAMPLE",
                std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - sample_started).count(),
                thread_cpu_us() - sample_cpu_started);
            {
                std::lock_guard<std::mutex> lock(g_snapshot_mutex);
                g_latest_snapshot = snapshot;
            }
            if (g_recording_enabled.load()) {
                append_performance_record(snapshot, client_uid);
            }
            const int interval = monotonic_ms() < g_sampling_lease_until_ms.load()
                ? g_interval_ms.load() : (g_recording_enabled.load() ? g_record_interval_ms.load() : 30000);
            const int generation = g_sampling_generation.load();
            std::unique_lock<std::mutex> wait_lock(g_sampling_mutex);
            g_sampling_changed.wait_for(wait_lock, std::chrono::milliseconds(interval), [&] {
                return !g_running || g_sampling_generation.load() != generation;
            });
        }
    });

    std::atomic<int> clients{0};
    std::mutex clients_mutex;
    std::condition_variable clients_done;
    while (g_running) {
        struct pollfd descriptor{server_fd, POLLIN, 0};
        if (poll(&descriptor, 1, 500) <= 0) continue;
        const int client_fd = accept(server_fd, nullptr, nullptr);
        if (client_fd < 0) continue;
        const auto auth_started = std::chrono::steady_clock::now();
        const auto auth_cpu_started = thread_cpu_us();
        const int caller_uid = silence::authenticate_client(client_fd, client_uid);
        silence::record_request_cost("AUTH",
            std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - auth_started).count(),
            thread_cpu_us() - auth_cpu_started);
        if (caller_uid < 0) {
            send_all(client_fd, "{\"type\":\"ERROR\",\"code\":\"UNAUTHORIZED\"}\n");
            close(client_fd); continue;
        }
        if (clients.load() >= 8) {
            send_all(client_fd, "{\"type\":\"ERROR\",\"code\":\"BUSY\"}\n");
            close(client_fd); continue;
        }
        clients.fetch_add(1);
        std::thread([&, client_fd, caller_uid] {
            handle_client(client_fd, started_at_epoch_ms, caller_uid);
            close(client_fd);
            { std::lock_guard<std::mutex> lock(clients_mutex); clients.fetch_sub(1); }
            clients_done.notify_one();
        }).detach();
    }

    {
        std::unique_lock<std::mutex> lock(clients_mutex);
        clients_done.wait(lock, [&] { return clients.load() == 0; });
    }
    if (sampler.joinable()) sampler.join();
    close(server_fd);
    close(lock_fd);
    if (filesystem_socket) unlink(socket_path.c_str());
    write_status_file(status_path, "stopped", 0, "daemon stopped");
    return 0;
}
