cmake_minimum_required(VERSION 3.22.1)

project(silence-daemon LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_executable(silence-daemon silence_daemon.cpp daemon_control.cpp)

target_compile_options(silence-daemon PRIVATE
    -Wall
    -Wextra
    -Werror=return-type
)

# The executable is packaged as an ABI-specific asset. It is copied after the
# native target is built so the App can extract and launch it through root.
set(DAEMON_ASSET_DIR
    "${CMAKE_SOURCE_DIR}/../../../build/generated/daemon-assets/${ANDROID_ABI}")

add_custom_command(TARGET silence-daemon POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${DAEMON_ASSET_DIR}"
    COMMAND ${CMAKE_COMMAND} -E copy
        "$<TARGET_FILE:silence-daemon>"
        "${DAEMON_ASSET_DIR}/silence-daemon"
    VERBATIM
)
