#include <android/native_window_jni.h>
#include <jni.h>

#include <cstdint>
#include <memory>

#include "common/log.h"
#include "gl/gl_renderer.h"
#include "renderer.h"
#include "vk/vk_renderer.h"

namespace {

// Kotlin 쪽 NativeRenderer 인스턴스 1개당 이 구조체 1개를 핸들로 넘긴다.
struct RendererHandle {
    std::unique_ptr<bc::IRenderer> impl;
    ANativeWindow* window = nullptr;
};

RendererHandle* fromHandle(jlong handle) {
    return reinterpret_cast<RendererHandle*>(static_cast<intptr_t>(handle));
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_kophas_battlecity_render_NativeRenderer_nativeCreate(JNIEnv*, jobject) {
    auto* handle = new RendererHandle();
    return static_cast<jlong>(reinterpret_cast<intptr_t>(handle));
}

JNIEXPORT jboolean JNICALL
Java_com_kophas_battlecity_render_NativeRenderer_nativeIsVulkanSupported(JNIEnv*, jclass) {
    return bc::vk::VulkanRenderer::isSupported() ? JNI_TRUE : JNI_FALSE;
}

// preferVulkan=false 이면 Vulkan 을 건너뛰고 바로 GLES 로 간다(디버그/강제 폴백용).
// 반환값은 bc::Backend 정수. 0 이면 두 백엔드 모두 실패.
JNIEXPORT jint JNICALL Java_com_kophas_battlecity_render_NativeRenderer_nativeAttachSurface(
        JNIEnv* env, jobject, jlong handle, jobject surface, jboolean preferVulkan) {
    RendererHandle* h = fromHandle(handle);
    if (h == nullptr) return 0;

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) {
        BC_LOGE("ANativeWindow_fromSurface 실패");
        return 0;
    }
    h->window = window;

    if (preferVulkan == JNI_TRUE) {
        auto vulkan = std::unique_ptr<bc::IRenderer>(new bc::vk::VulkanRenderer());
        if (vulkan->onSurfaceCreated(window)) {
            h->impl = std::move(vulkan);
            return static_cast<jint>(bc::Backend::kVulkan);
        }
        BC_LOGW("Vulkan 초기화 실패 - OpenGL ES 로 폴백한다");
        vulkan.reset();
    }

    auto gles = std::unique_ptr<bc::IRenderer>(new bc::gl::GlesRenderer());
    if (gles->onSurfaceCreated(window)) {
        h->impl = std::move(gles);
        return static_cast<jint>(bc::Backend::kGles);
    }

    BC_LOGE("Vulkan / OpenGL ES 모두 초기화 실패");
    ANativeWindow_release(window);
    h->window = nullptr;
    return 0;
}

JNIEXPORT void JNICALL Java_com_kophas_battlecity_render_NativeRenderer_nativeResize(
        JNIEnv*, jobject, jlong handle, jint width, jint height) {
    RendererHandle* h = fromHandle(handle);
    if (h == nullptr || !h->impl) return;
    h->impl->onSurfaceResized(width, height);
}

JNIEXPORT void JNICALL Java_com_kophas_battlecity_render_NativeRenderer_nativeDetachSurface(
        JNIEnv*, jobject, jlong handle) {
    RendererHandle* h = fromHandle(handle);
    if (h == nullptr) return;
    if (h->impl) {
        h->impl->onSurfaceDestroyed();
        h->impl.reset();
    }
    if (h->window != nullptr) {
        ANativeWindow_release(h->window);
        h->window = nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_kophas_battlecity_render_NativeRenderer_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    RendererHandle* h = fromHandle(handle);
    if (h == nullptr) return;
    if (h->impl) {
        h->impl->onSurfaceDestroyed();
        h->impl.reset();
    }
    if (h->window != nullptr) {
        ANativeWindow_release(h->window);
        h->window = nullptr;
    }
    delete h;
}

// pixels 는 반드시 direct ByteBuffer 여야 한다(복사 없이 읽는다).
JNIEXPORT jboolean JNICALL Java_com_kophas_battlecity_render_NativeRenderer_nativeUploadTexture(
        JNIEnv* env, jobject, jlong handle, jint textureId, jint width, jint height,
        jobject pixels, jboolean nearest) {
    RendererHandle* h = fromHandle(handle);
    if (h == nullptr || !h->impl) return JNI_FALSE;

    auto* data = static_cast<const uint8_t*>(env->GetDirectBufferAddress(pixels));
    if (data == nullptr) {
        BC_LOGE("텍스처 픽셀 버퍼가 direct ByteBuffer 가 아님");
        return JNI_FALSE;
    }
    return h->impl->uploadTexture(textureId, width, height, data, nearest == JNI_TRUE)
                   ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_kophas_battlecity_render_NativeRenderer_nativeReleaseTexture(
        JNIEnv*, jobject, jlong handle, jint textureId) {
    RendererHandle* h = fromHandle(handle);
    if (h == nullptr || !h->impl) return;
    h->impl->releaseTexture(textureId);
}

// sprites / runs 도 direct 버퍼다. 프레임마다 JNI 배열 복사가 일어나지 않도록
// Kotlin SpriteBatch 가 direct ByteBuffer 를 유지한다. (계획서 §25 Draw Call 최소화)
JNIEXPORT void JNICALL Java_com_kophas_battlecity_render_NativeRenderer_nativeRenderFrame(
        JNIEnv* env, jobject, jlong handle, jfloat clearR, jfloat clearG, jfloat clearB,
        jobject sprites, jint spriteCount, jobject runs, jint runCount, jint opaqueCount) {
    RendererHandle* h = fromHandle(handle);
    if (h == nullptr || !h->impl) return;

    const float* spriteData = nullptr;
    const int32_t* runData = nullptr;
    if (spriteCount > 0) {
        spriteData = static_cast<const float*>(env->GetDirectBufferAddress(sprites));
        runData = static_cast<const int32_t*>(env->GetDirectBufferAddress(runs));
        if (spriteData == nullptr || runData == nullptr) {
            BC_LOGE("스프라이트/런 버퍼가 direct ByteBuffer 가 아님");
            spriteCount = 0;
            runCount = 0;
        }
    }
    h->impl->renderFrame(clearR, clearG, clearB, spriteData, spriteCount, runData, runCount,
                         opaqueCount);
}

JNIEXPORT jint JNICALL Java_com_kophas_battlecity_render_NativeRenderer_nativeSurfaceWidth(
        JNIEnv*, jobject, jlong handle) {
    RendererHandle* h = fromHandle(handle);
    return (h != nullptr && h->impl) ? h->impl->surfaceWidth() : 0;
}

JNIEXPORT jint JNICALL Java_com_kophas_battlecity_render_NativeRenderer_nativeSurfaceHeight(
        JNIEnv*, jobject, jlong handle) {
    RendererHandle* h = fromHandle(handle);
    return (h != nullptr && h->impl) ? h->impl->surfaceHeight() : 0;
}

}  // extern "C"
