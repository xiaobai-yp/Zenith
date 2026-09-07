cmake_minimum_required(VERSION 3.18)
project(zenith_thermal C)

# NDK toolchain is set via android.toolchain.cmake
# Build with: cmake -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake ..

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# Compiler flags
add_compile_options(
    -Wall -Wextra -Werror
    -Wno-unused-parameter
    -fstack-protector-strong
    -D_FORTIFY_SOURCE=2
)

# Common include paths
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/lib
)

# ---- zenithd daemon executable ----
add_executable(zenithd
    daemon/zenithd.c
    daemon/crypto.c
    daemon/sysfs_monitor.c
    daemon/thermal_core.c
    daemon/battery_monitor.c
    daemon/app_monitor.c
    daemon/socket_server.c
    daemon/profile_engine.c
)

target_link_libraries(zenithd
    log     # Android logging (liblog)
    m       # math (battery_monitor uses float)
    cutils  # property_get/property_set
)

# ---- libzenith.so shared library ----
add_library(zenith SHARED
    daemon/sysfs_monitor.c
    daemon/thermal_core.c
    daemon/battery_monitor.c
    daemon/app_monitor.c
    daemon/socket_server.c
    daemon/profile_engine.c
    daemon/crypto.c
)

target_compile_definitions(zenith PRIVATE ZENITH_SHARED_LIB=1)

target_link_libraries(zenith
    log
    m
)

# Install
install(TARGETS zenithd DESTINATION bin)
install(TARGETS zenith DESTINATION lib)
