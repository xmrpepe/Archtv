cmake_minimum_required(VERSION 3.22.1)
project(archdock C CXX)

# Tuned for Google Tensor G4 (1x Cortex-X4, 3x A720, 4x A520 – all Armv9.2-A).
# Override with -DARCHDOCK_CPU=generic to build for other arm64 phones.
set(ARCHDOCK_CPU "cortex-x4" CACHE STRING "Value for -mcpu")
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(OPT_FLAGS "-O3 -fvisibility=hidden -ffunction-sections -fdata-sections")
if(NOT ARCHDOCK_CPU STREQUAL "generic")
    set(OPT_FLAGS "${OPT_FLAGS} -mcpu=${ARCHDOCK_CPU}")
endif()
set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} ${OPT_FLAGS}")
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} ${OPT_FLAGS}")
set(CMAKE_C_FLAGS_RELWITHDEBINFO "${CMAKE_C_FLAGS_RELWITHDEBINFO} ${OPT_FLAGS}")
set(CMAKE_CXX_FLAGS_RELWITHDEBINFO "${CMAKE_CXX_FLAGS_RELWITHDEBINFO} ${OPT_FLAGS}")

add_library(archdock SHARED
    audio_sink.cpp
    perf.cpp
)
target_link_libraries(archdock aaudio log android)
target_link_options(archdock PRIVATE "-Wl,--gc-sections" "-Wl,-z,max-page-size=16384")
