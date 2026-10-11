cmake_minimum_required(VERSION 3.22)
project(koggles_host_tests CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)
FetchContent_Declare(
  googletest
  GIT_REPOSITORY https://github.com/google/googletest.git
  GIT_TAG v1.15.2
)
set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
set(INSTALL_GTEST OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(googletest)

enable_testing()
include(GoogleTest)

set(CORE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/cpp/core)
file(GLOB CORE_SOURCES CONFIGURE_DEPENDS ${CORE_DIR}/*.cpp)

# Core sources are compiled into each executable directly, so the TSAN
# executable gets instrumented copies of them.
add_executable(core_tests
  audio_window_test.cpp
  device_params_test.cpp
  eye_layout_test.cpp
  ${CORE_SOURCES})
target_include_directories(core_tests PRIVATE ${CORE_DIR})
target_link_libraries(core_tests PRIVATE GTest::gtest_main)
gtest_discover_tests(core_tests)

find_package(Threads REQUIRED)
add_executable(audio_window_concurrency_tests
  audio_window_concurrency_test.cpp ${CORE_SOURCES})
target_include_directories(audio_window_concurrency_tests PRIVATE ${CORE_DIR})
target_compile_options(audio_window_concurrency_tests PRIVATE -fsanitize=thread -g)
target_link_options(audio_window_concurrency_tests PRIVATE -fsanitize=thread)
target_link_libraries(audio_window_concurrency_tests PRIVATE GTest::gtest_main Threads::Threads)
gtest_discover_tests(audio_window_concurrency_tests
  PROPERTIES ENVIRONMENT "TSAN_OPTIONS=halt_on_error=1:exitcode=66")
