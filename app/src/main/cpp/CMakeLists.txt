cmake_minimum_required(VERSION 3.22.1)
project(koggles LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# projectM options (GLES is forced on for Android by projectM itself).
set(ENABLE_PLAYLIST OFF CACHE BOOL "" FORCE)
set(ENABLE_SDL_UI OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_DOCS OFF CACHE BOOL "" FORCE)
set(ENABLE_DEBUG_POSTFIX OFF CACHE BOOL "" FORCE)
set(ENABLE_SYSTEM_PROJECTM_EVAL OFF CACHE BOOL "" FORCE)
set(ENABLE_SYSTEM_GLM OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
# Use projectm-eval's pre-generated parser/scanner; regenerating them with a host
# flex/bison would rewrite files inside the pinned submodule.
set(CMAKE_DISABLE_FIND_PACKAGE_FLEX ON CACHE BOOL "" FORCE)
set(CMAKE_DISABLE_FIND_PACKAGE_BISON ON CACHE BOOL "" FORCE)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/projectm ${CMAKE_BINARY_DIR}/projectm)

add_library(koggles SHARED
    jni/native_bridge.cpp
    renderer/renderer.cpp
)
target_link_libraries(koggles libprojectM::projectM GLESv3 EGL log android)
target_link_options(koggles PRIVATE -Wl,-z,max-page-size=16384)
