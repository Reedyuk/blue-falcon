cmake_minimum_required(VERSION 3.14)
project(bluefalcon-windows CXX)

set(CMAKE_CXX_STANDARD 17)
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
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
)

# Add Windows Runtime support
target_compile_options(bluefalcon-windows PRIVATE 
    /ZW  # Enable Windows Runtime extensions
    /EHsc  # Exception handling
    /await  # Enable coroutines
)

# Install the library
install(TARGETS bluefalcon-windows
    LIBRARY DESTINATION lib
    RUNTIME DESTINATION bin
)
