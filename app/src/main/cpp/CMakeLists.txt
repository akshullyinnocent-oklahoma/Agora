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

# Reproducible build: pin build info so it's independent of git state
# (F-Droid CI may not have git access to llama.cpp submodule)
set(LLAMA_BUILD_NUMBER 0)
set(LLAMA_BUILD_COMMIT "unknown")

# Set version variable needed by llama-common and mtmd CMakeLists.txt
set(LLAMA_INSTALL_VERSION "0.0.0")
# Build llama.cpp as a static library
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "")
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "")
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "")
set(BUILD_SHARED_LIBS OFF CACHE BOOL "")
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
add_subdirectory(${LLAMA_CPP_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama)
# Build llama-common (needed by mtmd CLI executables)
add_subdirectory(${LLAMA_CPP_DIR}/common ${CMAKE_CURRENT_BINARY_DIR}/common)
add_subdirectory(${LLAMA_CPP_DIR}/tools/mtmd ${CMAKE_CURRENT_BINARY_DIR}/mtmd)
# Exclude mtmd CLI executables from default build — they pull in common/arg.h,
# nlohmann/json, and other dependencies we don't need for the library.
foreach(cli llama-mtmd-cli llama-mtmd-debug
         llama-llava-cli llama-gemma3-cli llama-minicpmv-cli llama-qwen2vl-cli)
    if(TARGET ${cli})
        set_target_properties(${cli} PROPERTIES EXCLUDE_FROM_ALL TRUE)
    endif()
endforeach()
# Fix include paths for the CLIs (arg.h → common, nlohmann → vendor)
# deprecation warning CLIs just need arg.h
foreach(cli llama-llava-cli llama-gemma3-cli llama-minicpmv-cli llama-qwen2vl-cli)
    if(TARGET ${cli})
        target_include_directories(${cli} PRIVATE ${LLAMA_CPP_DIR}/common)
    endif()
endforeach()
# mtmd-cli needs common/chat.h + vendor/nlohmann
if(TARGET llama-mtmd-cli)
    target_include_directories(llama-mtmd-cli PRIVATE
        ${LLAMA_CPP_DIR}/common ${LLAMA_CPP_DIR}/vendor)
endif()
if(TARGET llama-mtmd-debug)
    target_include_directories(llama-mtmd-debug PRIVATE
        ${LLAMA_CPP_DIR}/common ${LLAMA_CPP_DIR}/vendor)
endif()

# Reproducible build: override ggml git commit embedded as #define
# ggml/CMakeLists.txt runs git rev-parse unconditionally and embeds
# the hash via target_compile_definitions(ggml-base PRIVATE GGML_COMMIT=...)
target_compile_definitions(ggml-base PRIVATE GGML_COMMIT="unknown")

# Build our JNI wrapper as a shared library
add_library(agora_llama SHARED llama_jni.cpp llama_chat_jni.cpp)
target_link_libraries(agora_llama llama mtmd log)

target_include_directories(agora_llama PRIVATE
    ${LLAMA_CPP_DIR}/include
    ${LLAMA_CPP_DIR}/tools/mtmd
)

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
