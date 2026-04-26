# Versión mínima de compilación
cmake_minimum_required(VERSION 3.22.1)

# Nombre de tu proyecto nativo
project("nicolas_llm")

# 1. Le decimos al compilador que incluya toda la carpeta de Llama
# Esto compilará automáticamente los archivos ggml y llama originales
set(LLAMA_BUILD_COMMON ON)
set(LLAMA_BUILD_TOOLS ON)
add_subdirectory(llama_cpp)

# 2. Declaramos nuestra propia librería puente (el archivo native-lib.cpp)
add_library(${CMAKE_PROJECT_NAME} SHARED
        native-lib.cpp)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        llama_cpp/tools/mtmd
        llama_cpp/include
        llama_cpp/ggml/include)

# 3. Vinculamos nuestro puente con el motor Llama y las librerías del sistema Android
target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        mtmd
        android
        jnigraphics
        log)
