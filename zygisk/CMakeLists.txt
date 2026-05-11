cmake_minimum_required(VERSION 3.18)
project(zdtd_zygisk LANGUAGES CXX)

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
  -nostdlib++
  -Wall
  -Wextra
)
target_link_options(zdtd_zygisk PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
set_target_properties(zdtd_zygisk PROPERTIES OUTPUT_NAME "arm64-v8a")
