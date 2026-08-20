#pragma once

#include <EGL/egl.h>
#include <GLES3/gl3.h>

#include <cstdint>
#include <unordered_map>
#include <vector>

#include "renderer.h"

namespace bc::gl {

// Vulkan 미지원/초기화 실패 기기를 위한 OpenGL ES 3.0 폴백. (계획서 §24)
// 정점 레이아웃과 스프라이트 입력 규약은 Vulkan 구현과 완전히 동일하다.
class GlesRenderer final : public IRenderer {
public:
    GlesRenderer() = default;
    ~GlesRenderer() override;

    Backend backend() const override { return Backend::kGles; }

    bool onSurfaceCreated(ANativeWindow* window) override;
    void onSurfaceResized(int32_t width, int32_t height) override;
    void onSurfaceDestroyed() override;

    bool uploadTexture(int32_t textureId, int32_t width, int32_t height,
                       const uint8_t* pixels) override;
    void releaseTexture(int32_t textureId) override;

    void renderFrame(float clearR, float clearG, float clearB,
                     const float* sprites, int32_t spriteCount,
                     const int32_t* runs, int32_t runCount) override;

    int32_t surfaceWidth() const override { return width_; }
    int32_t surfaceHeight() const override { return height_; }

private:
    static constexpr int kMaxQuads = 8192;

    bool createProgram();
    void querySurfaceSize();

    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLSurface surface_ = EGL_NO_SURFACE;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLConfig config_ = nullptr;

    GLuint program_ = 0;
    GLint uMvp_ = -1;
    GLint uTex_ = -1;
    GLuint vao_ = 0;
    GLuint vbo_ = 0;
    GLuint ibo_ = 0;

    std::unordered_map<int32_t, GLuint> textures_;
    std::vector<float> vertices_;

    int32_t width_ = 0;
    int32_t height_ = 0;
    bool ready_ = false;
};

}  // namespace bc::gl
