cmake_minimum_required(VERSION 3.10.2)
project("ollama-server")

# This is a dummy CMake project for build compatibility.
# Native binaries are bundled as prebuilt .so files in jniLibs.
add_library(ollama_stub SHARED dummy.cpp)