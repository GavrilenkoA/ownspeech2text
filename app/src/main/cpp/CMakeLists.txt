cmake_minimum_required(VERSION 3.22.1)
project(murmur_native LANGUAGES CXX)

add_library(murmur_native SHARED murmur_native.cpp)
target_compile_features(murmur_native PRIVATE cxx_std_20)
target_link_libraries(murmur_native PRIVATE log)
# NDK r28+ links 16 KB-page-aligned by default; keep it explicit for older NDKs.
target_link_options(murmur_native PRIVATE "-Wl,-z,max-page-size=16384")
