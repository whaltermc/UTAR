cmake_minimum_required(VERSION 3.22.1)
project(utarcore LANGUAGES CXX)

# libutarcore — the runner's core engine, called from Kotlin through JNI.
# Everything that is heavy, I/O bound or byte-precision lives here:
#   * Godot 4.1 PCK reader/writer (index parsing + sidecar cache, entry reads,
#     streaming multi-gigabyte rewrites, small override packs)
#   * GDExtension discovery: APK library scanning and ELF64 symbol extraction
#   * .gdextension config rewriting
#   * GDScript compatibility patching
#   * project.binary renderer-method alignment
#   * user:// save-data zip backup/restore
#   * page-cache warmup
# Kotlin keeps only the UI and Android framework glue (activities, Compose,
# SharedPreferences, ContentResolver, logging).

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(utarcore SHARED
        md5.cpp
        pck_engine.cpp
        library_set.cpp
        gdext_rewriter.cpp
        script_patcher.cpp
        settings_override.cpp
        save_zip.cpp
        warmup.cpp
        jni_bridge.cpp)

target_include_directories(utarcore PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
# Static libc++: the library must depend only on system libs so it loads identically on
# device (APK) and on the host JVM (unit tests).
target_link_options(utarcore PRIVATE -static-libstdc++)
target_link_libraries(utarcore z log)
