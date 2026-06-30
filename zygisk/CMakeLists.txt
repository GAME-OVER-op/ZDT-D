cmake_minimum_required(VERSION 3.18)
project(zdtd_zygisk LANGUAGES CXX)

# This CMake file is intended for Android/NDK builds only. A plain host CMake
# invocation can silently produce an x86_64 libarm64-v8a.so, which is invalid for
# Zygisk. Set ZDT_ZYGISK_ALLOW_HOST_CMAKE=1 only for local syntax experiments.
if(NOT ANDROID AND NOT DEFINED ENV{ZDT_ZYGISK_ALLOW_HOST_CMAKE})
  message(FATAL_ERROR "ZDT-D Zygisk must be built with an Android/NDK toolchain. Use ./build.sh for the checked arm64-v8a.so build, or set ZDT_ZYGISK_ALLOW_HOST_CMAKE=1 for non-release host checks.")
endif()

add_library(zdtd_zygisk SHARED src/main.cpp)
target_include_directories(zdtd_zygisk PRIVATE include)
target_compile_features(zdtd_zygisk PRIVATE cxx_std_17)
target_compile_options(zdtd_zygisk PRIVATE
  -fvisibility=hidden
  -fno-exceptions
  -fno-rtti
  -fno-threadsafe-statics
  -ffunction-sections
  -fdata-sections
  -Wall
  -Wextra
)
if(ANDROID_ABI)
  set(ZDT_ZYGISK_ABI_NAME "${ANDROID_ABI}")
else()
  set(ZDT_ZYGISK_ABI_NAME "arm64-v8a")
endif()

target_link_options(zdtd_zygisk PRIVATE
  -nostdlib++
  -Wl,--gc-sections
  -Wl,--exclude-libs,ALL
  -Wl,-soname,${ZDT_ZYGISK_ABI_NAME}.so
)
set_target_properties(zdtd_zygisk PROPERTIES
  PREFIX ""
  OUTPUT_NAME "${ZDT_ZYGISK_ABI_NAME}"
  SUFFIX ".so"
)
