cmake_minimum_required(VERSION 3.22.1)
project(edax_android LANGUAGES C CXX)

set(EDAX_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/edax/upstream/src")

add_library(edax_upstream STATIC "${EDAX_ROOT}/all.c")
target_include_directories(edax_upstream PUBLIC "${EDAX_ROOT}")
target_compile_features(edax_upstream PRIVATE c_std_17)
target_compile_definitions(edax_upstream PRIVATE EDAX_ANDROID_EMBEDDED=1 _GNU_SOURCE=1)
target_compile_options(edax_upstream PRIVATE
    -O2
    -fPIC
    -Wno-unused-parameter
    -Wno-missing-braces
    -Wno-parentheses
    -Wno-unused-function
)

add_library(edax_jni SHARED edax_android_bridge.c edax_jni.cpp)
target_include_directories(edax_jni PRIVATE "${EDAX_ROOT}")
target_compile_features(edax_jni PRIVATE cxx_std_17)
target_compile_definitions(edax_jni PRIVATE EDAX_ANDROID_EMBEDDED=1)
target_link_libraries(edax_jni PRIVATE edax_upstream android log m)
target_link_options(edax_jni PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

set_target_properties(edax_upstream edax_jni PROPERTIES
    C_VISIBILITY_PRESET hidden
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES
)
