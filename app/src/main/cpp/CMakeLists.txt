cmake_minimum_required(VERSION 3.22.1)
project(utarcore LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(utarcore SHARED
        core/md5.cpp
        core/pck_engine.cpp
        core/library_set.cpp
        core/gdext_rewriter.cpp
        core/save_zip.cpp
        jni/jni_common.cpp
        jni/jni_core.cpp
        jni/jni_pck.cpp
        jni/jni_library.cpp
        jni/jni_gdext.cpp
        jni/jni_script.cpp
        jni/jni_save.cpp
        jni/jni_md5.cpp
        jni/jni_copy.cpp)

target_include_directories(utarcore PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/core
        ${CMAKE_CURRENT_SOURCE_DIR}/jni)
target_link_options(utarcore PRIVATE -static-libstdc++)
target_link_libraries(utarcore z log)
