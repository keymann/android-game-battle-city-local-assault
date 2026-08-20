#pragma once

#include <android/native_window.h>
#include <cstdint>

namespace bc {

enum class Backend : int32_t {
    kNone = 0,
    kVulkan = 1,
    kGles = 2,
};

// 게임 로직과 렌더러를 분리하기 위한 추상 인터페이스. (계획서 §41-11)
// 구현체는 vk::VulkanRenderer / gl::GlesRenderer 이며 Kotlin 은
// 어느 쪽이 선택되었는지 Backend 값으로만 알 수 있다.
class IRenderer {
public:
    virtual ~IRenderer() = default;

    virtual Backend backend() const = 0;

    // 서피스 수명. 실패하면 false 를 돌려주고 호출자가 폴백을 시도한다.
    virtual bool onSurfaceCreated(ANativeWindow* window) = 0;
    virtual void onSurfaceResized(int32_t width, int32_t height) = 0;
    virtual void onSurfaceDestroyed() = 0;

    // 텍스처. pixels 는 RGBA8888 tightly packed.
    // nearest=true 는 픽셀아트용(도트 유지), false 는 벡터풍 스프라이트용(회전 시 매끈).
    virtual bool uploadTexture(int32_t textureId, int32_t width, int32_t height,
                               const uint8_t* pixels, bool nearest) = 0;
    virtual void releaseTexture(int32_t textureId) = 0;

    // 프레임. sprites 는 kFloatsPerSprite 간격의 float 배열,
    // runs 는 [textureId, count] 쌍의 int 배열.
    // opaqueCount 는 앞에서부터 블렌딩 없이 그려도 되는 스프라이트 수다(지형 레이어).
    virtual void renderFrame(float clearR, float clearG, float clearB,
                             const float* sprites, int32_t spriteCount,
                             const int32_t* runs, int32_t runCount,
                             int32_t opaqueCount) = 0;

    virtual int32_t surfaceWidth() const = 0;
    virtual int32_t surfaceHeight() const = 0;
};

}  // namespace bc
