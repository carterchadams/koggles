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

# projectM's version.h.in reads CMAKE_PROJECT_VERSION_* (the top-level project's
# version), so give it projectM's own pinned version.
set(CMAKE_PROJECT_VERSION 4.1.7)
set(CMAKE_PROJECT_VERSION_MAJOR 4)
set(CMAKE_PROJECT_VERSION_MINOR 1)
set(CMAKE_PROJECT_VERSION_PATCH 7)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/projectm ${CMAKE_BINARY_DIR}/projectm)

# Cardboard SDK: libGfxPluginCardboard.so is extracted from the sub-built AAR by
# the Gradle task extractCardboardNative.
add_library(cardboard_api SHARED IMPORTED)
set_target_properties(cardboard_api PROPERTIES
    IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../../../build/cardboard/jni/${ANDROID_ABI}/libGfxPluginCardboard.so
    INTERFACE_INCLUDE_DIRECTORIES ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/cardboard/sdk/include
)

add_library(koggles SHARED
    core/audio_window.cpp
    core/device_params.cpp
    core/eye_layout.cpp
    jni/native_bridge.cpp
    renderer/engine.cpp
    renderer/eye_capture.cpp
    renderer/quad.cpp
    renderer/goggles.cpp
    renderer/renderer.cpp
)
target_include_directories(koggles PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/core)
target_link_libraries(koggles libprojectM::projectM cardboard_api GLESv3 EGL log android)
target_link_options(koggles PRIVATE -Wl,-z,max-page-size=16384)
