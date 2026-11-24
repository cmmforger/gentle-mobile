cmake_minimum_required(VERSION 3.22.1)
project(gentle_whisper LANGUAGES C CXX)

# The CPU-only ggml build: static, no accelerators, no host-specific
# instruction tuning, no OpenMP — the same flags whisper.cpp's own Android
# example uses, minus OpenMP so the .so carries no libomp dependency.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_STATIC ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_CPU_REPACK OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_KOMPUTE OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)
set(GGML_SYCL OFF CACHE BOOL "" FORCE)
set(GGML_HIP OFF CACHE BOOL "" FORCE)
set(GGML_MUSA OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)

add_subdirectory(whisper/ggml ggml)

add_library(whisper STATIC
    whisper/src/whisper.cpp
    whisper/src/whisper-arch.h
)
target_include_directories(whisper PUBLIC
    whisper/include
    whisper/src
    whisper/ggml/include
    whisper/ggml/src
)
target_compile_features(whisper PUBLIC cxx_std_11)
target_link_libraries(whisper PUBLIC ggml)
target_compile_definitions(whisper PRIVATE GGML_USE_CPU)

add_library(whisper_jni SHARED jni/whisper_jni.c)
target_include_directories(whisper_jni PRIVATE whisper/include)
target_link_libraries(whisper_jni PRIVATE whisper ggml android log)

# Keep the .so small and symbol-clean, the same way whisper.cpp's Android
# example ships: hidden visibility, function sections, dead-code GC.
set_target_properties(whisper_jni whisper ggml PROPERTIES
    C_VISIBILITY_PRESET hidden
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)
target_compile_options(whisper_jni PRIVATE -ffunction-sections -fdata-sections)
target_link_options(whisper_jni PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
