#include "daemon_json.hpp"
#include <arpa/inet.h>
#include <cerrno>
#include <csignal>
#include <cstring>
#include <fcntl.h>
#include <iostream>
#include <stdexcept>
#include <string>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>

int main(int argc, char** argv) {
    if (argc == 2 && std::string(argv[1]) == "--json-tests") {
        using silence::Json;
        int passed = 0;
        const std::string valid = R"({"command":"PING","中文":"冻结","rows":[1,true,null]})";
        auto value = Json::parse(valid);
        if (value.at("中文").text() != "冻结" || Json::parse(value.dump()).dump() != value.dump()) return 1;
        ++passed;
        for (const auto& invalid : {"{\"uid\":1,\"uid\":2}", "[01]", "[1,]", "{}{}", "[9223372036854775808]", "{\"s\":\"bad\ntext\"}"}) {
            try { Json::parse(invalid); return 2; } catch (const std::exception&) { ++passed; }
        }
        try { Json::parse(std::string(25, '[') + "0" + std::string(25, ']')); return 3; } catch (const std::exception&) { ++passed; }
        std::cout << "json_tests_passed=" << passed << '\n'; return 0;
    }
    if (argc == 2 && std::string(argv[1]) == "--child") {
        if (setresgid(19999, 19999, 19999) || setresuid(19999, 19999, 19999)) return 4;
        prctl(PR_SET_NAME, "SilenceProbe", 0, 0, 0);
        for (;;) pause();
    }
    if (argc == 2 && std::string(argv[1]) == "--spawn") {
        const std::string group = "/sys/fs/cgroup/apps/uid_19999";
        if (mkdir(group.c_str(), 0755) != 0) { std::cerr << "test uid group already exists or cannot be created errno=" << errno << '\n'; return 5; }
        pid_t child = fork();
        if (child == 0) {
            int null_fd = open("/dev/null", O_RDWR);
            if (null_fd >= 0) { dup2(null_fd, 0); dup2(null_fd, 1); dup2(null_fd, 2); if (null_fd > 2) close(null_fd); }
            const char* args[] = {"cn.himpqblog.silence.migrationprobe", "--child", nullptr};
            execv(argv[0], const_cast<char* const*>(args)); _exit(127);
        }
        if (child < 0) { rmdir(group.c_str()); return 6; }
        const std::string subgroup = group + "/pid_" + std::to_string(child);
        if (mkdir(subgroup.c_str(), 0755) != 0) { kill(child, SIGKILL); rmdir(group.c_str()); return 7; }
        const int fd = open((subgroup + "/cgroup.procs").c_str(), O_WRONLY);
        const auto pid = std::to_string(child);
        if (fd < 0 || write(fd, pid.data(), pid.size()) != static_cast<ssize_t>(pid.size())) {
            if (fd >= 0) close(fd); kill(child, SIGKILL); rmdir(subgroup.c_str()); rmdir(group.c_str()); return 8;
        }
        close(fd); std::cout << child << '\n'; return 0;
    }
    if (argc != 4 || std::string(argv[1]) != "--client") return 9;
    int uid = std::stoi(argv[2]), port = std::stoi(argv[3]);
    if (setresgid(uid, uid, uid) || setresuid(uid, uid, uid)) return 10;
    const int fd = socket(AF_INET, SOCK_STREAM, 0);
    timeval timeout{3, 0}; setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    sockaddr_in addr{}; addr.sin_family = AF_INET; addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK); addr.sin_port = htons(port);
    if (connect(fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr))) { std::cerr << "connect_errno=" << errno << '\n'; return 11; }
    std::string request; std::getline(std::cin, request); request += '\n';
    size_t offset = 0;
    while (offset < request.size()) {
        auto count = send(fd, request.data() + offset, request.size() - offset, MSG_NOSIGNAL);
        if (count <= 0) return 12; offset += count;
    }
    std::string response; char bytes[4096];
    while (response.size() <= 1024 * 1024) {
        auto count = recv(fd, bytes, sizeof(bytes), 0);
        if (count <= 0) break;
        response.append(bytes, count);
        if (response.find('\n') != std::string::npos) break;
    }
    close(fd); std::cout << response; return response.empty() ? 13 : 0;
}
