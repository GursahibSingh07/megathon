cmake_minimum_required(VERSION 3.22.1)
project(whisper_integration LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(WHISPER_DIR "${CMAKE_CURRENT_LIST_DIR}")
set(TFLITE_DIR "${WHISPER_DIR}/tf-lite-api")

include_directories(
    "${TFLITE_DIR}/tensorflow_src"
    "${TFLITE_DIR}/include/flatbuffers/include"
)

add_library(tflite SHARED IMPORTED)
set_target_properties(tflite PROPERTIES
    IMPORTED_LOCATION
    "${CMAKE_CURRENT_LIST_DIR}/../../jniLibs/${ANDROID_ABI}/libtensorflowlite.so"
)

add_library(native-lib SHARED
    "${WHISPER_DIR}/native-lib.cpp"
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(native-lib
    ${log-lib}
    ${android-lib}
    tflite
)
