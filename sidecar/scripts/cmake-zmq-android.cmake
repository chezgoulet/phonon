# Pre-load snippet for prima.cpp build: add ZMQ include/library paths
# for the cross-compiled libzmq.a. Pass with -C on the cmake command line.

set(ZMQ_DIR "$ENV{REPO_ROOT}/.deps/zmq-android")

if(EXISTS "${ZMQ_DIR}/include/zmq.h" AND EXISTS "${ZMQ_DIR}/lib/libzmq.a")
  include_directories(BEFORE SYSTEM "${ZMQ_DIR}/include")
  link_directories("${ZMQ_DIR}/lib")
  set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY "BOTH" CACHE STRING "" FORCE)
  set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE "BOTH" CACHE STRING "" FORCE)
  message(STATUS "ZMQ for Android found at ${ZMQ_DIR}")
else()
  message(FATAL_ERROR "ZMQ for Android NOT found at ${ZMQ_DIR}")
endif()
