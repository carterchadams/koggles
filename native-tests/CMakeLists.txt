cmake_minimum_required(VERSION 3.22)
project(koggles_host_tests LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

include(FetchContent)
FetchContent_Declare(
  googletest
  URL https://github.com/google/googletest/archive/refs/tags/v1.15.2.tar.gz
)
set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
set(INSTALL_GTEST OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(googletest)

enable_testing()
include(GoogleTest)

set(CORE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/cpp/core)
file(GLOB CORE_SOURCES CONFIGURE_DEPENDS ${CORE_DIR}/*.cpp)
file(GLOB TEST_SOURCES CONFIGURE_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/*_test.cpp)

add_executable(koggles_host_tests ${TEST_SOURCES} ${CORE_SOURCES})
target_include_directories(koggles_host_tests PRIVATE ${CORE_DIR})
target_compile_options(koggles_host_tests PRIVATE -Wall -Wextra)
target_link_libraries(koggles_host_tests PRIVATE GTest::gtest_main)
gtest_discover_tests(koggles_host_tests)
