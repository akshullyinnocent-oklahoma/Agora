cmake_minimum_required(VERSION 3.22)
project("agora_llama")

set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../thirdparty/llama.cpp" CACHE PATH "llama.cpp source directory")

# Resolve to canonical absolute paths (required for -ffile-prefix-map matching)
get_filename_component(LLAMA_CPP_DIR "${LLAMA_CPP_DIR}" REALPATH)

# Reproducible build: deterministic source paths, no debug info, no .comment section
add_compile_options(
    -ffile-prefix-map=${CMAKE_SOURCE_DIR}=.
    -ffile-prefix-map=${LLAMA_CPP_DIR}=llama.cpp
    -g0
    -fno-ident
)

# Build llama.cpp as a static library
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "")
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "")
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "")
set(BUILD_SHARED_LIBS OFF CACHE BOOL "")
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
add_subdirectory(${LLAMA_CPP_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama)

# Build our JNI wrapper as a shared library
add_library(agora_llama SHARED llama_jni.cpp llama_chat_jni.cpp)
target_link_libraries(agora_llama llama log)

target_include_directories(agora_llama PRIVATE ${LLAMA_CPP_DIR}/include)

# Reproducible build: deterministic linker output
target_link_options(agora_llama PRIVATE
    -Wl,--build-id=none
    -Wl,--hash-style=gnu
    -Wl,--icf=all
    -Wl,--threads=1
)

# Reproducible build: set SOURCE_DATE_EPOCH for deterministic timestamps
if(DEFINED ENV{SOURCE_DATE_EPOCH})
    add_definitions(-DSOURCE_DATE_EPOCH=$ENV{SOURCE_DATE_EPOCH})
endif()

# Reproducible build: remove .comment section using NDK tool
find_program(LLVM_OBJCOPY llvm-objcopy
    HINTS ${ANDROID_NDK}/toolchains/llvm/prebuilt/*/bin
    NO_DEFAULT_PATH
)
if(LLVM_OBJCOPY)
    add_custom_command(TARGET agora_llama POST_BUILD
        COMMAND ${LLVM_OBJCOPY} --remove-section .comment $<TARGET_FILE:agora_llama>
    )
endif()
