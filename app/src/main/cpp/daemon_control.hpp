#pragma once
#include "daemon_json.hpp"
#include <string>

namespace silence {
void initialize_control(int app_uid, const std::string& log_path);
int authenticate_client(int fd, int app_uid);
Json control_request(const Json& request, int caller_uid);
std::string foreground_package();
Json control_metrics();
void record_request_cost(const std::string& command, long long wall_us, long long cpu_us);
}
