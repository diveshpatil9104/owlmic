cmake_minimum_required(VERSION 3.16)
project(owlmic C CXX)

set(CMAKE_CXX_STANDARD 17)

# libopus from the submodule (git submodule update --init), statically linked.
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
add_subdirectory(opus EXCLUDE_FROM_ALL)

# Oboe from its prefab package.
find_package(oboe REQUIRED CONFIG)

add_library(owlmic SHARED opus_jni.c oboe_jni.cpp)
target_link_libraries(owlmic PRIVATE opus oboe::oboe)
