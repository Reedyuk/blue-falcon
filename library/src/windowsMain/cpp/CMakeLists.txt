cmake_minimum_required(VERSION 3.14)
project(bluefalcon-windows CXX)

# Minimum Windows version: 10.0.17763 (Windows 10 October 2018 Update / 1809).
# All required Bluetooth LE GATT APIs are available from this SDK onwards.
if(NOT CMAKE_SYSTEM_VERSION)
    set(CMAKE_SYSTEM_VERSION "10.0.17763.0")
endif()

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Find JNI
find_package(JNI REQUIRED)
include_directories(${JNI_INCLUDE_DIRS})

# Add source files
set(SOURCES
    BluetoothLEManager.cpp
    jni_bridge.cpp
)

# Create shared library
add_library(bluefalcon-windows SHARED ${SOURCES})

# Link against required Windows libraries
target_link_libraries(bluefalcon-windows 
    ${JNI_LIBRARIES}
    WindowsApp
)

# Set C++/WinRT properties
set_target_properties(bluefalcon-windows PROPERTIES
    CXX_STANDARD 20
    CXX_STANDARD_REQUIRED ON
)

# C++/WinRT is a header-only projection; it requires C++20 coroutines and
# standard exception handling. /ZW enables C++/CX (a separate, deprecated
# dialect) which conflicts with C++/WinRT types and requires platform.winmd.
# /await is superseded by C++20 coroutines. Neither flag should be used here.
target_compile_options(bluefalcon-windows PRIVATE
    /EHsc
)

# Install the library
install(TARGETS bluefalcon-windows
    LIBRARY DESTINATION lib
    RUNTIME DESTINATION bin
)
