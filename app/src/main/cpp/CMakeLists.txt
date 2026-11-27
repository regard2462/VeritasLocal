cmake_minimum_required(VERSION 3.22.1)
project(veritas_llama LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Keine Pfade des Bau-Rechners in den Bibliotheken: Quell- und NDK-Pfade werden durch kurze Namen ersetzt
if(CMAKE_C_COMPILER_ID MATCHES "Clang|GNU")
    set(_prefix_maps "${CMAKE_SOURCE_DIR}=src")
    if(ANDROID_NDK)
        list(APPEND _prefix_maps "${ANDROID_NDK}=ndk")
        file(TO_NATIVE_PATH "${ANDROID_NDK}" _ndk_native)
        list(APPEND _prefix_maps "${_ndk_native}=ndk")
    endif()
    file(TO_NATIVE_PATH "${CMAKE_SOURCE_DIR}" _src_native)
    list(APPEND _prefix_maps "${_src_native}=src")
    foreach(_map IN LISTS _prefix_maps)
        add_compile_options("-ffile-prefix-map=${_map}")
    endforeach()
endif()

# llama.cpp als Submodul unter app/src/main/cpp/third_party/llama.cpp
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
add_subdirectory(third_party/llama.cpp)

add_library(veritas_llama SHARED llama_jni.cpp)

target_include_directories(veritas_llama PRIVATE
    third_party/llama.cpp/include
    third_party/llama.cpp/ggml/include
)

if(ANDROID)
    find_library(log-lib log)
endif()

target_link_libraries(veritas_llama
    llama
    ggml
    ${log-lib}
)

# Desktop: JNI-Header aus dem JDK; unter Linux abhaengige Bibliotheken im selben Ordner suchen
if(NOT ANDROID)
    find_package(JNI REQUIRED)
    target_include_directories(veritas_llama PRIVATE ${JNI_INCLUDE_DIRS})
    if(UNIX)
        set_target_properties(veritas_llama PROPERTIES BUILD_RPATH "$ORIGIN" INSTALL_RPATH "$ORIGIN")
    endif()
endif()
