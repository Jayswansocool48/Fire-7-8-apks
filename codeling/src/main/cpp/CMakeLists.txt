# The tablet AI: llama.cpp plus a small JNI wrapper (llama_engine.cpp).
# llama.cpp itself is fetched into ./llama.cpp by CI (see .github/workflows/build-apk.yml),
# at the version in LLAMA_CPP_VERSION.
cmake_minimum_required(VERSION 3.22.1)
project(codelingai C CXX)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp build-llama)

add_library(codelingai SHARED llama_engine.cpp)
target_link_libraries(codelingai llama log)
target_link_options(codelingai PRIVATE "-Wl,--gc-sections" "-Wl,-z,max-page-size=16384")
