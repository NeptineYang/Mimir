# SPAKE2 的 JNI 桥（vendor 自 Shizuku manager/src/main/jni/adb_pairing.cpp，Apache-2.0）。
#
# 只保留 "adb" 这个共享库本体：上游同一个 CMakeLists 还编了 starter/misc/selinux 等
# 启动 server 用的可执行文件，Mimir 不走那条路，全部删掉。
#
# 依赖说明：BoringSSL 由 io.github.vvb2060.ndk:boringssl 的 prefab 包提供，
# find_package 能直接命中 prefab 里的 boringssl::crypto_static。
cmake_minimum_required(VERSION 3.22)

project("mimir_adblink")

set(CMAKE_CXX_STANDARD 17)

# 不加上游的 -Werror=format：外来代码在不同 NDK 版本下的格式告警不值得阻塞构建。
add_compile_options(-fdata-sections -ffunction-sections -fno-exceptions -fno-rtti -fno-threadsafe-statics)

find_package(boringssl REQUIRED CONFIG)

# 库名决定 System.loadLibrary 的参数，必须与 AdbPairingClient 保持一致。
add_library(mimir_adb SHARED adb_pairing.cpp)

target_link_libraries(mimir_adb log boringssl::crypto_static)
