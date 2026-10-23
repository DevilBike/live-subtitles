cmake_minimum_required(VERSION 3.22.1)
project(livesubs_whisper C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include(FetchContent)

# whisper.cpp (and the ggml copy inside it) is fetched at configure time and
# built from source with the NDK. Bump the tag to upgrade.
FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG        v1.9.4
    GIT_SHALLOW    TRUE
)

# One static build of whisper + ggml linked into our JNI library. CPU only:
# phone GPUs don't help much at these model sizes and add a lot of setup.
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE) # ggml's own thread pool, no libomp to ship
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)

# ARMv8.2 dot product + fp16 roughly doubles speed for quantized models. Every
# phone core newer than Cortex-A53 has them; the app checks /proc/cpuinfo before
# loading this library. Build with -Pwhisper.armArch=armv8-a for older phones.
if(ANDROID_ABI STREQUAL "arm64-v8a" AND LIVESUBS_ARM_ARCH)
    set(GGML_CPU_ARM_ARCH ${LIVESUBS_ARM_ARCH} CACHE STRING "" FORCE)
endif()

FetchContent_MakeAvailable(whisper)

add_library(livesubs_whisper SHARED whisper_jni.cpp)
target_link_libraries(livesubs_whisper PRIVATE whisper)
if(ANDROID)
    target_link_libraries(livesubs_whisper PRIVATE log)
    target_link_options(livesubs_whisper PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL
        # Loadable on Android 15+ phones that use 16 KB memory pages.
        -Wl,-z,max-page-size=16384)
endif()
