cmake_minimum_required(VERSION 3.22.1)
project(compyterm C CXX)

set(GHOSTTY_DIR "${GHOSTTY_OUT}/${ANDROID_ABI}")

add_library(ghostty-vt STATIC IMPORTED)
set_target_properties(ghostty-vt PROPERTIES IMPORTED_LOCATION "${GHOSTTY_DIR}/lib/libghostty-vt.a")

add_library(compyterm SHARED compyterm.c stub.cpp)
target_compile_definitions(compyterm PRIVATE GHOSTTY_STATIC)
target_include_directories(compyterm PRIVATE "${GHOSTTY_DIR}/include")
target_link_libraries(compyterm ghostty-vt log jnigraphics)
target_link_options(compyterm PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--gc-sections" "-s")
