#
# Copyright (C) 2024 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

cmake_minimum_required(VERSION 3.10.2)

set(CMAKE_CXX_STANDARD 14)

project(dav1dJNI C CXX)

if(${ANDROID_ABI} MATCHES "armeabi-v7a")
    add_compile_options("-mfpu=neon")
    add_compile_options("-marm")
    add_compile_options("-fPIC")
endif()

string(TOLOWER "${CMAKE_BUILD_TYPE}" build_type)
if(build_type MATCHES "^rel")
    add_compile_options("-O3")
endif()

set(dav1d_jni_root "${CMAKE_CURRENT_SOURCE_DIR}")

# Path to compiled static libdav1d.
set(DAV1D_LIB_PATH "${dav1d_jni_root}/nativelib/${ANDROID_ABI}/libdav1d.a")

# Add the include directory from dav1d.
include_directories("${dav1d_jni_root}/dav1d/include" "${dav1d_jni_root}/dav1d")

# Build dav1dJNI shared library.
add_library(dav1dJNI
            SHARED
            dav1d_jni.cc
            cpu_info.cc
            cpu_info.h)

set_target_properties(dav1dJNI PROPERTIES
                      CXX_VISIBILITY_PRESET default
                      VISIBILITY_INLINES_HIDDEN OFF)

# Locate NDK libraries.
find_library(android_log_lib log)
find_library(android_native_window_lib android)

# Link dav1dJNI against used libraries.
target_link_libraries(dav1dJNI
                      PRIVATE ${android_native_window_lib}
                      PRIVATE ${DAV1D_LIB_PATH}
                      PRIVATE ${android_log_lib})
