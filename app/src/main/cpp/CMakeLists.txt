cmake_minimum_required(VERSION 3.22.1)
project("howard")

# llama.cpp submodule
set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")

# Build llama.cpp as a static library
add_subdirectory(${LLAMA_DIR} llama.cpp)

# JNI bridge
add_library(howard_jni SHARED howard_jni.cpp)

target_include_directories(howard_jni PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/ggml/include
)

target_link_libraries(howard_jni
    llama
    ggml
    android
    log
)
