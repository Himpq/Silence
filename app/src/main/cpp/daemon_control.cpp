#include "daemon_control.hpp"
#include <algorithm>
#include <arpa/inet.h>
#include <atomic>
#include <chrono>
#include <cerrno>
#include <cstring>
#include <deque>
#include <dirent.h>
#include <fcntl.h>
#include <fstream>
#include <map>
#include <memory>
#include <csignal>
#include <mutex>
#include <set>
#include <sstream>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

namespace silence {
namespace {
int app_uid = -1;
std::string runtime_directory;
std::mutex state_mutex;
std::mutex configuration_mutex;
Json configuration = Json::dict();
long long config_version = 0;
std::string foreground;
long long foreground_at = 0;
long long foreground_sequence = 0;
std::string force_poll_token;
std::map<std::string, Json> runtime_states;
std::set<std::string> requested_states;
std::deque<std::string> hook_logs;
long long hook_seen_at = 0;
std::set<std::string> managed_packages;
struct TargetState {
    std::mutex mutex;
    long long last_version = 0;
    long long protect_until = 0;
};
std::map<int, std::shared_ptr<TargetState>> targets;
struct Metric { long long count = 0, wall_us = 0, cpu_us = 0, max_wall_us = 0; };
std::map<std::string, Metric> metrics;

long long now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
}
long long steady_ms() {
    timespec value{}; clock_gettime(CLOCK_BOOTTIME, &value);
    return value.tv_sec * 1000LL + value.tv_nsec / 1000000;
}
std::string read_file(const std::string& path, size_t limit = 65536) {
    std::ifstream in(path, std::ios::binary);
    if (!in) throw std::runtime_error("read_failed:" + path + ":errno=" + std::to_string(errno));
    std::string out(limit, '\0'); in.read(out.data(), limit); out.resize(in.gcount()); return out;
}
bool atomic_write(const std::string& path, const std::string& value) {
    const std::string temp = path + ".tmp";
    const int fd = open(temp.c_str(), O_CREAT | O_WRONLY | O_TRUNC | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0) return false;
    size_t offset = 0;
    while (offset < value.size()) {
        ssize_t n = write(fd, value.data() + offset, value.size() - offset);
        if (n <= 0) { close(fd); unlink(temp.c_str()); return false; }
        offset += n;
    }
    bool ok = fsync(fd) == 0; close(fd);
    if (ok) ok = rename(temp.c_str(), path.c_str()) == 0;
    if (!ok) unlink(temp.c_str());
    return ok;
}
bool safe_package(const std::string& p) {
    return !p.empty() && p.size() < 256 && p.find('.') != std::string::npos &&
        std::all_of(p.begin(), p.end(), [](unsigned char c) { return std::isalnum(c) || c == '_' || c == '.'; });
}
int target_uid(const Json& r) {
    long long uid = r.at("uid").integer();
    if (uid < 10000 || uid > INT_MAX || uid == app_uid) throw std::runtime_error("invalid_target_uid");
    if (!safe_package(r.at("package").text()) || r.at("package").text() == "cn.himpqblog.silence") throw std::runtime_error("invalid_target_package");
    return static_cast<int>(uid);
}
Json response(const std::string& type) { Json r = Json::dict(); r["type"] = type; r["success"] = true; return r; }
Json failure(const std::string& code) { Json r = response("ERROR"); r["success"] = false; r["code"] = code; r["detail"] = code; return r; }

struct Proc {
    int pid = 0, uid = -1;
    long long rss_kb = 0, ticks = 0, start_ticks = 0;
    std::string name;
    bool frozen = false, parent_frozen = false;
};
std::string cgroup_path(int uid, int pid = 0) {
    return "/sys/fs/cgroup/apps/uid_" + std::to_string(uid) + (pid ? "/pid_" + std::to_string(pid) : "") + "/cgroup.freeze";
}
bool freeze_flag(const std::string& path) {
    std::ifstream in(path); int state = -1;
    return (in >> state) && state == 1;
}
bool read_proc(int pid, Proc& p) {
    try {
        p.pid = pid;
        const auto base = "/proc/" + std::to_string(pid);
        std::istringstream status(read_file(base + "/status", 16384));
        std::string line;
        while (std::getline(status, line)) {
            if (line.rfind("Uid:", 0) == 0) { std::istringstream v(line.substr(4)); v >> p.uid; }
            if (line.rfind("VmRSS:", 0) == 0) { std::istringstream v(line.substr(6)); v >> p.rss_kb; }
        }
        std::string cmd = read_file(base + "/cmdline", 4096);
        p.name = cmd.substr(0, cmd.find('\0'));
        if (p.uid < 10000 || p.name.empty()) return false;
        std::string stat = read_file(base + "/stat", 4096);
        auto end = stat.rfind(") ");
        if (end == std::string::npos) return false;
        std::istringstream values(stat.substr(end + 2));
        std::vector<std::string> fields; std::string v;
        while (values >> v) fields.push_back(v);
        if (fields.size() < 20) return false;
        p.ticks = std::stoll(fields[11]) + std::stoll(fields[12]);
        p.start_ticks = std::stoll(fields[19]);
        p.parent_frozen = freeze_flag(cgroup_path(p.uid));
        p.frozen = p.parent_frozen || freeze_flag(cgroup_path(p.uid, p.pid));
        return true;
    } catch (...) { return false; } // A process exiting during a snapshot is not a collector error.
}
Json proc_json(const Proc& p) {
    Json r = Json::dict();
    r["pid"] = p.pid; r["uid"] = p.uid; r["name"] = p.name; r["rssKb"] = p.rss_kb;
    r["ticks"] = p.ticks; r["startTicks"] = p.start_ticks; r["frozen"] = p.frozen; r["parentFrozen"] = p.parent_frozen;
    return r;
}
std::vector<Proc> uid_processes(int uid) {
    // Android's freezer hierarchy is the index. Do not scan every process for
    // every package in a background poll.
    const std::string dir = "/sys/fs/cgroup/apps/uid_" + std::to_string(uid);
    DIR* d = opendir(dir.c_str());
    if (!d) {
        if (errno == ENOENT) return {};
        throw std::runtime_error("cgroup_open_failed:errno=" + std::to_string(errno));
    }
    std::vector<Proc> result;
    while (dirent* e = readdir(d)) {
        std::string name = e->d_name;
        if (name.rfind("pid_", 0) != 0) continue;
        try { Proc p; if (read_proc(std::stoi(name.substr(4)), p) && p.uid == uid) result.push_back(std::move(p)); } catch (...) {}
    }
    closedir(d); return result;
}
bool belongs(const Proc& p, int uid, const std::string& package) {
    return p.uid == uid && (p.name == package || p.name.rfind(package + ':', 0) == 0);
}
std::shared_ptr<TargetState> target_state(int uid) {
    std::lock_guard<std::mutex> lock(state_mutex);
    auto& target = targets[uid];
    if (!target) target = std::make_shared<TargetState>();
    return target;
}
Json freeze(const Json& r, int caller) {
    const int uid = target_uid(r);
    const std::string package = r.at("package").text();
    const bool frozen = r.at("freeze").flag();
    const long long version = r.at("version").integer();
    const long long deadline = r.at("deadlineMs").integer();
    if (deadline <= 0) return failure("invalid_request_version");
    // 客户端版本只用来识别明显异常的值，不再参与排序。daemon 侧本来就按 uid 持锁串行执行，
    // hook 侧也用 pending 表保证同一个包只有一条命令在途，因此不需要 last_version 做硬性拒绝：
    // 任何客户端都能把 version 顶到未来，从而把这个 uid 永久锁死成 stale_request，直到 daemon
    // 重启才恢复。合法客户端传的是 SystemClock.elapsedRealtimeNanos()，与 steady_ms() 同源，
    // 只差量纲，所以这里按"不能超出当前时间 60 秒"来兜底。
    if (version <= 0 || version > steady_ms() * 1000000LL + 60LL * 1000000000LL)
        return failure("implausible_version");
    auto state = target_state(uid);
    std::lock_guard<std::mutex> target_lock(state->mutex);
    if (steady_ms() > deadline) return failure("request_expired");
    state->last_version = version;
    // Only a genuine pre-launch thaw re-arms the launch guard, and it never extends a
    // window that is already open. A normal thaw must stay side-effect free: the hook
    // reconciles thaws on every foreground transition, and re-arming here kept every
    // later freeze blocked as launch_protected. The app path is never launch guarded.
    const bool hook = caller == 1000 || caller == 0;
    if (!frozen && hook && r.has("prelaunch") && r.at("prelaunch").flag())
        state->protect_until = std::max(state->protect_until, steady_ms() + 15000);
    if (frozen && hook && steady_ms() < state->protect_until) return failure("launch_protected");
    {
        std::lock_guard<std::mutex> lock(state_mutex);
        if (frozen && foreground == package) return failure("foreground_protected");
        if (caller == 1000 && frozen) {
            if (!configuration.has("rules") || !configuration.at("rules").has("apps") ||
                !configuration.at("rules").at("apps").has(package)) return failure("rule_not_configured");
            const auto& rule = configuration.at("rules").at("apps").at(package);
            if (rule.has("whitelist") && rule.at("whitelist").flag()) return failure("whitelisted");
            if (!configuration.has("globals") || configuration.at("globals").at("silence_hook_enabled").text() != "1" ||
                configuration.at("globals").at("silence_freeze_hook_enabled").text() != "1") return failure("freeze_disabled");
        }
    }
    const Json& selected = r.at("targets");
    if (selected.kind != Json::Array || selected.array.size() > 128) return failure("invalid_targets");
    std::set<std::string> names;
    for (const auto& value : selected.array) names.insert(value.text());
    bool all = names.empty() || names.count("ALL");
    std::vector<Proc> processes;
    for (const Proc& p : uid_processes(uid)) {
        if (!belongs(p, uid, package)) continue;
        auto colon = p.name.find(':');
        auto display = colon == std::string::npos ? "main" : p.name.substr(colon + 1);
        if (all || names.count(p.name) || names.count(display)) processes.push_back(p);
    }
    if (processes.empty() && frozen) return failure("no_target_processes");
    Json result = response("FREEZE_RESULT"); result["writes"] = 0; result["targets"] = Json::list();
    int writes = 0, errors = 0;
    auto write_state = [&](const std::string& path, int expected, const Proc* original) {
        Json row = Json::dict(); row["path"] = path; row["action"] = "already";
        if (original) row["pid"] = original->pid;
        const int fd = open(path.c_str(), O_RDWR | O_CLOEXEC | O_NOFOLLOW);
        if (fd < 0) {
            if (!frozen && errno == ENOENT) { row["action"] = "exited"; result["targets"].array.push_back(row); return; }
            row["errno"] = errno; row["action"] = "open_failed"; ++errors; result["targets"].array.push_back(row); return;
        }
        char buffer[8]{};
        ssize_t count = pread(fd, buffer, sizeof(buffer) - 1, 0);
        const int before = count > 0 ? buffer[0] - '0' : -1; row["before"] = before;
        if (original) {
            Proc current;
            if (!read_proc(original->pid, current) || !belongs(current, uid, package) || current.start_ticks != original->start_ticks) {
                close(fd); row["action"] = "identity_changed"; ++errors; result["targets"].array.push_back(row); return;
            }
        }
        if (steady_ms() > deadline) { close(fd); row["action"] = "request_expired"; ++errors; result["targets"].array.push_back(row); return; }
        if (before != expected) {
            const char value[2] = {static_cast<char>('0' + expected), '\n'};
            if (pwrite(fd, value, sizeof(value), 0) == sizeof(value)) { ++writes; row["action"] = "written"; }
            else { row["errno"] = errno; row["action"] = "write_failed"; ++errors; }
        }
        std::memset(buffer, 0, sizeof(buffer)); count = pread(fd, buffer, sizeof(buffer) - 1, 0); close(fd);
        const int after = count > 0 ? buffer[0] - '0' : -1;
        row["after"] = after; row["verified"] = after == expected;
        if (after != expected) ++errors;
        result["targets"].array.push_back(row);
    };
    // A frozen parent defeats a child-only thaw. Thaw it before the children.
    if (!frozen) write_state(cgroup_path(uid), 0, nullptr);
    for (const auto& p : processes) write_state(cgroup_path(uid, p.pid), frozen ? 1 : 0, &p);
    result["success"] = errors == 0; result["writes"] = writes; result["errors"] = errors;
    result["detail"] = "uid=" + std::to_string(uid) + " selected=" + std::to_string(processes.size()) +
        " writes=" + std::to_string(writes) + " errors=" + std::to_string(errors) + " verified=" + (errors == 0 ? "true" : "false");
    if (!errors) { std::lock_guard<std::mutex> lock(state_mutex); if (frozen) managed_packages.insert(package); else managed_packages.erase(package); }
    return result;
}
Json processes(const Json& r) {
    Json result = response("PROCESSES"); result["processes"] = Json::list();
    if (r.has("uid")) {
        int uid = target_uid(r); std::string package = r.at("package").text();
        for (const auto& p : uid_processes(uid)) if (belongs(p, uid, package)) result["processes"].array.push_back(proc_json(p));
    } else {
        DIR* d = opendir("/proc");
        if (!d) return failure("proc_unavailable");
        while (dirent* e = readdir(d)) {
            std::string n = e->d_name;
            if (n.empty() || !std::all_of(n.begin(), n.end(), [](unsigned char c) { return std::isdigit(c); })) continue;
            try { Proc p; if (read_proc(std::stoi(n), p)) result["processes"].array.push_back(proc_json(p)); } catch (...) {}
        }
        closedir(d);
    }
    std::istringstream stat(read_file("/proc/stat", 1024)); std::string cpu; stat >> cpu;
    long long total = 0, value = 0;
    for (int i = 0; i < 8 && stat >> value; ++i) total += value;
    result["totalTicks"] = total;
    std::lock_guard<std::mutex> lock(state_mutex); result["managedPackages"] = Json::list();
    for (const auto& p : managed_packages) result["managedPackages"].array.push_back(p);
    return result;
}
Json oom(const Json& r) {
    int uid = target_uid(r); const std::string package = r.at("package").text();
    long long pid_value = r.at("pid").integer();
    if (pid_value <= 0 || pid_value > INT_MAX) return failure("invalid_pid");
    int pid = static_cast<int>(pid_value); auto state = target_state(uid);
    std::lock_guard<std::mutex> target_lock(state->mutex);
    Proc original;
    if (!read_proc(pid, original) || !belongs(original, uid, package) || original.name != r.at("processName").text()) return failure("process_identity_mismatch");
    // A directory handle stays attached to this process, even if its PID is reused.
    const int dir = open(("/proc/" + std::to_string(pid)).c_str(), O_DIRECTORY | O_RDONLY | O_CLOEXEC);
    if (dir < 0) return failure("process_exited");
    const int fd = openat(dir, "oom_score_adj", (r.has("adj") ? O_RDWR : O_RDONLY) | O_CLOEXEC | O_NOFOLLOW);
    close(dir);
    if (fd < 0) return failure("oom_open_failed:errno=" + std::to_string(errno));
    char buffer[32]{}; ssize_t n = pread(fd, buffer, sizeof(buffer) - 1, 0);
    int before = n > 0 ? std::stoi(buffer) : 0;
    if (r.has("adj")) {
        long long adj = r.at("adj").integer();
        if (adj < -1000 || adj > 1000) { close(fd); return failure("invalid_oom_adj"); }
        Proc current;
        if (!read_proc(pid, current) || current.start_ticks != original.start_ticks || !belongs(current, uid, package)) { close(fd); return failure("process_identity_changed"); }
        auto text = std::to_string(adj) + '\n';
        if (pwrite(fd, text.data(), text.size(), 0) != static_cast<ssize_t>(text.size())) { close(fd); return failure("oom_write_failed"); }
    }
    std::memset(buffer, 0, sizeof(buffer)); n = pread(fd, buffer, sizeof(buffer) - 1, 0); close(fd);
    if (n <= 0) return failure("oom_readback_failed");
    Json result = response("OOM_RESULT"); result["before"] = before; result["after"] = std::stoi(buffer);
    result["success"] = !r.has("adj") || result.at("after").integer() == r.at("adj").integer(); result["detail"] = "verified_native_oom";
    return result;
}
bool write_priority_setting(const std::string& value) {
    pid_t pid = fork();
    if (pid < 0) return false;
    if (pid == 0) {
        int null_fd = open("/dev/null", O_RDWR);
        if (null_fd >= 0) { dup2(null_fd, 0); dup2(null_fd, 1); dup2(null_fd, 2); }
        execl("/system/bin/settings", "settings", "put", "global", "silence_process_priority_rules_b64", value.c_str(), nullptr);
        _exit(127);
    }
    int status = 0; const auto deadline = steady_ms() + 1500;
    while (steady_ms() < deadline) {
        if (waitpid(pid, &status, WNOHANG) == pid) return WIFEXITED(status) && WEXITSTATUS(status) == 0;
        usleep(10000);
    }
    kill(pid, SIGKILL); waitpid(pid, &status, 0); return false;
}
Json external_mode(const Json& r);
Json apply_configured_external_mode();

Json sync_config(const Json& r) {
    std::lock_guard<std::mutex> config_lock(configuration_mutex);
    const Json& next = r.at("config");
    if (next.kind != Json::Object || next.at("rules").at("apps").kind != Json::Object ||
        next.at("globals").kind != Json::Object) return failure("invalid_configuration");
    const std::set<std::string> keys = {"silence_hook_poll_interval_seconds", "silence_hook_enabled", "silence_freeze_hook_enabled", "silence_performance_hook_enabled", "silence_process_debug_log_enabled", "silence_process_priority_rules_b64"};
    for (const auto& field : next.at("globals").object) if (!keys.count(field.first) || field.second.kind != Json::String) return failure("invalid_config_key");
    for (const auto& key : keys) if (!next.at("globals").has(key)) return failure("missing_config_key");
    for (const auto& entry : next.at("rules").at("apps").object) {
        if (!safe_package(entry.first) || entry.second.kind != Json::Object) return failure("invalid_rule_package");
        if (entry.second.at("freeze_processes").kind != Json::Array || entry.second.at("dont_freeze_when").kind != Json::Array || entry.second.at("whitelist").kind != Json::Boolean) return failure("invalid_rule");
    }
    auto valid_mode = [](const Json& mode) { return mode.kind == Json::String && (mode.text() == "powersave" || mode.text() == "balance" || mode.text() == "performance" || mode.text() == "fast"); };
    if (!valid_mode(next.at("externalDefault")) || next.at("externalModes").kind != Json::Object) return failure("invalid_external_modes");
    for (const auto& mode : next.at("externalModes").object) if (!safe_package(mode.first) || !valid_mode(mode.second)) return failure("invalid_external_modes");
    const std::string external_path = next.at("externalPath").text();
    if (!external_path.empty() && (external_path[0] != '/' || external_path.find("/../") != std::string::npos || external_path.find('\0') != std::string::npos ||
        external_path.rfind("/proc/", 0) == 0 || external_path.rfind("/sys/", 0) == 0 || external_path.rfind("/dev/", 0) == 0)) return failure("invalid_external_path");
    std::string previous;
    {
        std::lock_guard<std::mutex> lock(state_mutex);
        if (configuration.dump() == next.dump()) { auto result = response("CONFIG_RESULT"); result["version"] = config_version; result["changed"] = false; return result; }
        if (configuration.has("globals")) previous = configuration.at("globals").at("silence_process_priority_rules_b64").text();
    }
    const std::string priority = next.at("globals").at("silence_process_priority_rules_b64").text();
    if (priority != previous && !write_priority_setting(priority)) return failure("priority_settings_sync_failed");
    long long version;
    { std::lock_guard<std::mutex> lock(state_mutex); version = config_version + 1; }
    Json persisted = response("CONFIG"); persisted["version"] = version; persisted["config"] = next;
    if (!atomic_write(runtime_directory + "/config.json", persisted.dump())) return failure("config_persist_failed");
    {
        std::lock_guard<std::mutex> lock(state_mutex); configuration = next; config_version = version;
    }
    auto result = response("CONFIG_RESULT"); result["version"] = version; result["changed"] = true;

    auto external = apply_configured_external_mode();
    result["externalApplied"] = external.at("success");
    if (external.has("detail")) result["externalDetail"] = external.at("detail");
    return result;
}
Json external_mode(const Json& r) {
    std::string path;
    { std::lock_guard<std::mutex> lock(state_mutex); if (configuration.has("externalPath")) path = configuration.at("externalPath").text(); }
    if (path.empty()) return failure("external_mode_not_configured");
    const std::string value = r.at("mode").text();
    if (value != "powersave" && value != "balance" && value != "performance" && value != "fast") return failure("invalid_external_mode");
    const int fd = open(path.c_str(), O_RDWR | O_CREAT | O_CLOEXEC | O_NOFOLLOW, 0644);
    if (fd < 0) return failure("external_open_failed:errno=" + std::to_string(errno));
    char existing[32]{};
    const ssize_t count = pread(fd, existing, sizeof(existing), 0);
    if (count >= 0 && std::string(existing, count) == value) { close(fd); return response("EXTERNAL_MODE_RESULT"); }
    bool ok = ftruncate(fd, 0) == 0 && write(fd, value.data(), value.size()) == static_cast<ssize_t>(value.size()); close(fd);
    return ok ? response("EXTERNAL_MODE_RESULT") : failure("external_write_failed");
}
Json apply_configured_external_mode() {
    std::string mode;
    {
        std::lock_guard<std::mutex> lock(state_mutex);
        if (!configuration.has("externalPath") || configuration.at("externalPath").text().empty()) return response("EXTERNAL_DISABLED");
        mode = configuration.at("externalDefault").text();
        if (configuration.at("externalModes").has(foreground)) mode = configuration.at("externalModes").at(foreground).text();
    }
    Json request = Json::dict(); request["mode"] = mode; return external_mode(request);
}

}

void initialize_control(int uid, const std::string&) {
    app_uid = uid;
    runtime_directory = "/data/local/tmp/silence-control-" + std::to_string(uid);
    if (mkdir(runtime_directory.c_str(), 0700) != 0 && errno != EEXIST) throw std::runtime_error("control_directory_failed");
    struct stat st{};
    if (lstat(runtime_directory.c_str(), &st) != 0 || !S_ISDIR(st.st_mode) || st.st_uid != 0 || (st.st_mode & 0077)) throw std::runtime_error("unsafe_control_directory");
    if (access((runtime_directory + "/config.json").c_str(), F_OK) == 0) {
        Json stored = Json::parse(read_file(runtime_directory + "/config.json", 512 * 1024));
        configuration = stored.at("config"); config_version = stored.at("version").integer();
    }
}

int authenticate_client(int fd, int uid) {
    sockaddr_in peer{}, local{}; socklen_t size = sizeof(peer);
    if (getpeername(fd, reinterpret_cast<sockaddr*>(&peer), &size) != 0 || peer.sin_family != AF_INET || peer.sin_addr.s_addr != htonl(INADDR_LOOPBACK)) return -1;
    size = sizeof(local);
    if (getsockname(fd, reinterpret_cast<sockaddr*>(&local), &size) != 0) return -1;
    // The UID comes from the kernel's client socket entry, never from a request
    // field or a predictable port. Keep the accepted connection open throughout
    // this lookup. Both IPv4 and mapped IPv6 Java sockets use this same tuple.
    for (const auto& table : {std::string("/proc/net/tcp"), std::string("/proc/net/tcp6")}) {
        std::ifstream in(table); std::string line;
        while (std::getline(in, line)) {
            std::istringstream fields(line); std::string index, client, server, state, queues, timer, retransmit; int owner = -1;
            if (!(fields >> index >> client >> server >> state >> queues >> timer >> retransmit >> owner)) continue;
            auto matches = [](const std::string& endpoint, int port) {
                auto colon = endpoint.find(':'); if (colon == std::string::npos) return false;
                std::string address = endpoint.substr(0, colon);
                if (address != "0100007F" && address != "0000000000000000FFFF00000100007F") return false;
                try { return std::stoi(endpoint.substr(colon + 1), nullptr, 16) == port; } catch (...) { return false; }
            };
            if (state == "01" && matches(client, ntohs(peer.sin_port)) && matches(server, ntohs(local.sin_port))) {
                return owner == uid || owner == 1000 || owner == 0 ? owner : -1;
            }
        }
    }
    return -1;
}

Json control_request(const Json& r, int caller) {
    try {
        const std::string command = r.at("command").text();
        const bool app = caller == app_uid || caller == 0;
        const bool hook = caller == 1000 || caller == 0;
        if (command == "SET_FREEZE") return freeze(r, caller);
        if (command == "GET_PROCESSES" && app) return processes(r);
        if (command == "OOM" && app) return oom(r);
        if (command == "SYNC_CONFIG" && app) return sync_config(r);
        if (command == "SET_EXTERNAL_MODE" && app) return external_mode(r);
        if (command == "FORCE_POLL" && app) {
            std::lock_guard<std::mutex> lock(state_mutex); force_poll_token = std::to_string(now_ms()); return response("FORCE_POLL_RESULT");
        }
        if (command == "GET_CONFIG") {
            std::lock_guard<std::mutex> lock(state_mutex); auto result = response("CONFIG"); result["config"] = configuration; result["version"] = config_version; return result;
        }
        if (command == "REPORT_FOREGROUND" && hook) {
            const std::string package = r.at("package").text();
            if (!package.empty() && !safe_package(package)) return failure("invalid_package");
            const long long sequence = r.at("version").integer();
            bool changed;
            {
                std::lock_guard<std::mutex> lock(state_mutex);
                if (sequence <= foreground_sequence) return failure("stale_foreground");
                changed = foreground != package;
                foreground_sequence = sequence; foreground = package; foreground_at = now_ms();
            }
            auto result = response("FOREGROUND_RESULT");
            if (changed) {
                auto external = apply_configured_external_mode();
                result["externalApplied"] = external.at("success");
                if (external.has("detail")) result["externalDetail"] = external.at("detail");
            }
            return result;
        }
        if (command == "HOOK_WORK" && hook) {
            std::lock_guard<std::mutex> lock(state_mutex); hook_seen_at = now_ms();
            auto result = response("HOOK_WORK"); result["version"] = config_version; result["foregroundPackage"] = foreground;
            if (r.at("configVersion").integer() != config_version) result["config"] = configuration;
            result["forcePoll"] = force_poll_token; result["runtimeRequests"] = Json::list();
            for (const auto& package : requested_states) result["runtimeRequests"].array.push_back(package);
            requested_states.clear();
            if (r.has("logs") && r.at("logs").kind == Json::Array && r.at("logs").array.size() <= 200) {
                for (const auto& item : r.at("logs").array) {
                    std::string text = item.text(); if (text.size() <= 2048) hook_logs.push_back(text);
                }
                while (hook_logs.size() > 500) hook_logs.pop_front();
            }
            return result;
        }
        if (command == "REPORT_RUNTIME" && hook) {
            if (r.at("states").kind != Json::Object || r.at("states").object.size() > 128) return failure("invalid_runtime_states");
            std::lock_guard<std::mutex> lock(state_mutex);
            for (const auto& entry : r.at("states").object) if (safe_package(entry.first)) { runtime_states[entry.first] = entry.second; runtime_states[entry.first]["timestampMs"] = now_ms(); }
            return response("RUNTIME_RESULT");
        }
        if (command == "GET_RUNTIME" && app) {
            const std::string package = r.at("package").text(); if (!safe_package(package)) return failure("invalid_package");
            std::lock_guard<std::mutex> lock(state_mutex);
            if (requested_states.size() < 128) requested_states.insert(package);
            auto result = response("RUNTIME"); auto it = runtime_states.find(package);
            result["known"] = it != runtime_states.end() && now_ms() - it->second.at("timestampMs").integer() < 60000;
            if (result.at("known").flag()) result["state"] = it->second;
            return result;
        }
        if (command == "GET_LOGS" && app) {
            std::lock_guard<std::mutex> lock(state_mutex); auto result = response("LOGS"); result["events"] = Json::list();
            result["hookActive"] = hook_seen_at > 0 && now_ms() - hook_seen_at < 15000;
            for (const auto& line : hook_logs) result["events"].array.push_back(line);
            return result;
        }
        return failure("command_not_authorized");
    } catch (const std::exception& e) { return failure(e.what()); }
}
std::string foreground_package() { std::lock_guard<std::mutex> lock(state_mutex); return foreground; }
void record_request_cost(const std::string& command, long long wall, long long cpu) {
    std::lock_guard<std::mutex> lock(state_mutex);
    auto& m = metrics[metrics.size() < 32 || metrics.count(command) ? command : "OTHER"]; ++m.count; m.wall_us += wall; m.cpu_us += cpu; m.max_wall_us = std::max(m.max_wall_us, wall);
}
Json control_metrics() {
    std::lock_guard<std::mutex> lock(state_mutex); Json r = Json::dict(); r["configVersion"] = config_version;
    r["hookLastSeenMs"] = hook_seen_at; r["foregroundUpdatedAtMs"] = foreground_at;
    r["metrics"] = Json::dict();
    for (const auto& v : metrics) { Json m = Json::dict(); m["count"] = v.second.count; m["wallUs"] = v.second.wall_us; m["cpuUs"] = v.second.cpu_us; m["maxWallUs"] = v.second.max_wall_us; r["metrics"][v.first] = m; }
    return r;
}
}
