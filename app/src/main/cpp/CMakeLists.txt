cmake_minimum_required(VERSION 3.22.1)
project(battlecity LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# GLSL -> SPIR-V -> C 헤더
# ---------------------------------------------------------------------------
find_program(GLSLC glslc
        HINTS "${ANDROID_NDK}/shader-tools/${ANDROID_HOST_TAG}"
        REQUIRED)

set(GENERATED_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated")
file(MAKE_DIRECTORY "${GENERATED_DIR}")

function(compile_shader STAGE_FILE SYMBOL OUT_VAR)
    get_filename_component(NAME_ONLY "${STAGE_FILE}" NAME)
    set(SPV "${GENERATED_DIR}/${NAME_ONLY}.spv")
    set(HDR "${GENERATED_DIR}/${NAME_ONLY}.h")
    add_custom_command(
            OUTPUT "${HDR}"
            COMMAND "${GLSLC}" -O "${CMAKE_CURRENT_SOURCE_DIR}/${STAGE_FILE}" -o "${SPV}"
            COMMAND "${CMAKE_COMMAND}" -DIN=${SPV} -DOUT=${HDR} -DSYM=${SYMBOL}
                    -P "${CMAKE_CURRENT_SOURCE_DIR}/bin2c.cmake"
            DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/${STAGE_FILE}"
                    "${CMAKE_CURRENT_SOURCE_DIR}/bin2c.cmake"
            COMMENT "glslc ${NAME_ONLY}"
            VERBATIM)
    set(${OUT_VAR} "${HDR}" PARENT_SCOPE)
endfunction()

compile_shader(shaders/sprite.vert kSpriteVertSpv VERT_HDR)
compile_shader(shaders/sprite.frag kSpriteFragSpv FRAG_HDR)

add_custom_target(shaders DEPENDS "${VERT_HDR}" "${FRAG_HDR}")

# ---------------------------------------------------------------------------
# 라이브러리
# ---------------------------------------------------------------------------
add_library(battlecity SHARED
        jni_bridge.cpp
        vk/vk_renderer.cpp
        gl/gl_renderer.cpp)

add_dependencies(battlecity shaders)

target_include_directories(battlecity PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}"
        "${GENERATED_DIR}")

find_library(log-lib log)
find_library(android-lib android)
find_library(egl-lib EGL)
find_library(gles-lib GLESv3)
find_library(vulkan-lib vulkan)

target_link_libraries(battlecity
        ${log-lib}
        ${android-lib}
        ${egl-lib}
        ${gles-lib}
        ${vulkan-lib})

# libvulkan.so 는 API 24+ 에서 항상 존재한다(드라이버가 없으면 로더가 실패를 반환).
# 따라서 링크는 안전하고, 실제 지원 여부는 런타임에 vkCreateInstance /
# vkEnumeratePhysicalDevices 결과로 판정하여 GLES 로 폴백한다. (계획서 §24)
target_compile_definitions(battlecity PRIVATE VK_USE_PLATFORM_ANDROID_KHR)
