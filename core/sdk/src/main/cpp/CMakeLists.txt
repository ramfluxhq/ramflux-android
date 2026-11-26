# SPDX-License-Identifier: AGPL-3.0-or-later
cmake_minimum_required(VERSION 3.22.1)
project(ramflux_android LANGUAGES CXX)
if(NOT DEFINED RAMFLUX_NATIVE_DIR OR NOT DEFINED RAMFLUX_HEADER_DIR)
    message(FATAL_ERROR "Use scripts/build-native.sh with the matching SDK library and generated header")
endif()
add_library(ramflux_sdk SHARED IMPORTED)
# build-native.sh sets the SDK SONAME; the archive guard verifies portable linkage.
set_target_properties(ramflux_sdk PROPERTIES
    IMPORTED_LOCATION "${RAMFLUX_NATIVE_DIR}/arm64-v8a/libramflux_sdk.so")
add_library(ramflux_android SHARED bridge.cpp custody_jni.cpp)
target_compile_features(ramflux_android PRIVATE cxx_std_17)
target_compile_options(ramflux_android PRIVATE -Wall -Wextra -Werror)
target_include_directories(ramflux_android PRIVATE "${RAMFLUX_HEADER_DIR}")
target_link_libraries(ramflux_android PRIVATE ramflux_sdk)
target_link_options(ramflux_android PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")

# The realrun card gallery binding. Off unless build-native.sh runs with RAMFLUX_CARD_FIXTURE=1; the release library never
# carries it.
option(RAMFLUX_CARD_FIXTURE "Build the test-only card fixture library" OFF)
if(RAMFLUX_CARD_FIXTURE)
    add_library(ramflux_card_fixture SHARED card_fixture_jni.cpp)
    target_compile_features(ramflux_card_fixture PRIVATE cxx_std_17)
    target_compile_definitions(ramflux_card_fixture PRIVATE RAMFLUX_WITH_CARD_RUNTIME)
    target_compile_options(ramflux_card_fixture PRIVATE -Wall -Wextra -Werror)
    target_include_directories(ramflux_card_fixture PRIVATE "${RAMFLUX_HEADER_DIR}")
    target_link_libraries(ramflux_card_fixture PRIVATE ramflux_sdk)
    target_link_options(ramflux_card_fixture PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
endif()
