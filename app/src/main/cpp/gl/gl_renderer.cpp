#include "gl/gl_renderer.h"

#include <cmath>
#include <cstring>

#include "common/log.h"
#include "common/sprite.h"

namespace bc::gl {
namespace {

constexpr int kFloatsPerVertex = 8;
constexpr int kVerticesPerQuad = 4;
constexpr int kIndicesPerQuad = 6;

const char* const kVertexSource = R"(#version 300 es
layout(location = 0) in vec2 inPos;
layout(location = 1) in vec2 inUV;
layout(location = 2) in vec4 inColor;
uniform mat4 uMvp;
out vec2 vUV;
out vec4 vColor;
void main() {
    gl_Position = uMvp * vec4(inPos, 0.0, 1.0);
    vUV = inUV;
    vColor = inColor;
}
)";

const char* const kFragmentSource = R"(#version 300 es
precision mediump float;
uniform sampler2D uTex;
in vec2 vUV;
in vec4 vColor;
out vec4 outColor;
void main() {
    // discard 는 타일 기반 GPU 에서 early-Z 를 무력화한다.
    // 알파 블렌딩이 이미 투명 픽셀을 처리하므로 쓰지 않는다.
    outColor = texture(uTex, vUV) * vColor;
}
)";

GLuint compile(GLenum stage, const char* source) {
    GLuint shader = glCreateShader(stage);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint ok = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (ok != GL_TRUE) {
        char info[1024] = {0};
        glGetShaderInfoLog(shader, sizeof(info) - 1, nullptr, info);
        BC_LOGE("셰이더 컴파일 실패: %s", info);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

}  // namespace

GlesRenderer::~GlesRenderer() { onSurfaceDestroyed(); }

bool GlesRenderer::onSurfaceCreated(ANativeWindow* window) {
    if (window == nullptr) return false;

    display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display_ == EGL_NO_DISPLAY) {
        BC_LOGE("eglGetDisplay 실패");
        return false;
    }
    if (eglInitialize(display_, nullptr, nullptr) != EGL_TRUE) {
        BC_LOGE("eglInitialize 실패");
        return false;
    }

    const EGLint configAttribs[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                                    EGL_SURFACE_TYPE,    EGL_WINDOW_BIT,
                                    EGL_RED_SIZE,        8,
                                    EGL_GREEN_SIZE,      8,
                                    EGL_BLUE_SIZE,       8,
                                    EGL_ALPHA_SIZE,      8,
                                    EGL_DEPTH_SIZE,      0,
                                    EGL_STENCIL_SIZE,    0,
                                    EGL_NONE};
    EGLint configCount = 0;
    if (eglChooseConfig(display_, configAttribs, &config_, 1, &configCount) != EGL_TRUE ||
        configCount == 0) {
        BC_LOGE("eglChooseConfig 실패");
        return false;
    }

    EGLint nativeVisualId = 0;
    eglGetConfigAttrib(display_, config_, EGL_NATIVE_VISUAL_ID, &nativeVisualId);
    ANativeWindow_setBuffersGeometry(window, 0, 0, nativeVisualId);

    surface_ = eglCreateWindowSurface(display_, config_, window, nullptr);
    if (surface_ == EGL_NO_SURFACE) {
        BC_LOGE("eglCreateWindowSurface 실패");
        return false;
    }

    const EGLint contextAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, contextAttribs);
    if (context_ == EGL_NO_CONTEXT) {
        BC_LOGE("eglCreateContext 실패");
        return false;
    }
    if (eglMakeCurrent(display_, surface_, surface_, context_) != EGL_TRUE) {
        BC_LOGE("eglMakeCurrent 실패");
        return false;
    }

    if (!createProgram()) return false;

    glGenVertexArrays(1, &vao_);
    glBindVertexArray(vao_);

    glGenBuffers(1, &vbo_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glBufferData(GL_ARRAY_BUFFER,
                 static_cast<GLsizeiptr>(kMaxQuads) * kVerticesPerQuad * kFloatsPerVertex *
                         sizeof(float),
                 nullptr, GL_DYNAMIC_DRAW);

    const GLsizei stride = kFloatsPerVertex * sizeof(float);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, stride, reinterpret_cast<void*>(0));
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, stride,
                          reinterpret_cast<void*>(2 * sizeof(float)));
    glEnableVertexAttribArray(2);
    glVertexAttribPointer(2, 4, GL_FLOAT, GL_FALSE, stride,
                          reinterpret_cast<void*>(4 * sizeof(float)));

    std::vector<uint16_t> indices(static_cast<size_t>(kMaxQuads) * kIndicesPerQuad);
    for (int quad = 0; quad < kMaxQuads; ++quad) {
        const uint16_t base = static_cast<uint16_t>(quad * kVerticesPerQuad);
        const size_t offset = static_cast<size_t>(quad) * kIndicesPerQuad;
        indices[offset + 0] = base + 0;
        indices[offset + 1] = base + 1;
        indices[offset + 2] = base + 2;
        indices[offset + 3] = base + 2;
        indices[offset + 4] = base + 3;
        indices[offset + 5] = base + 0;
    }
    glGenBuffers(1, &ibo_);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo_);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER,
                 static_cast<GLsizeiptr>(indices.size() * sizeof(uint16_t)), indices.data(),
                 GL_STATIC_DRAW);
    glBindVertexArray(0);

    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glEnable(GL_BLEND);
    glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

    vertices_.resize(static_cast<size_t>(kMaxQuads) * kVerticesPerQuad * kFloatsPerVertex);

    querySurfaceSize();
    ready_ = true;
    BC_LOGI("OpenGL ES 렌더러 준비 완료 (%dx%d)", width_, height_);
    return true;
}

bool GlesRenderer::createProgram() {
    GLuint vs = compile(GL_VERTEX_SHADER, kVertexSource);
    if (vs == 0) return false;
    GLuint fs = compile(GL_FRAGMENT_SHADER, kFragmentSource);
    if (fs == 0) {
        glDeleteShader(vs);
        return false;
    }
    program_ = glCreateProgram();
    glAttachShader(program_, vs);
    glAttachShader(program_, fs);
    glLinkProgram(program_);
    glDeleteShader(vs);
    glDeleteShader(fs);

    GLint ok = GL_FALSE;
    glGetProgramiv(program_, GL_LINK_STATUS, &ok);
    if (ok != GL_TRUE) {
        char info[1024] = {0};
        glGetProgramInfoLog(program_, sizeof(info) - 1, nullptr, info);
        BC_LOGE("프로그램 링크 실패: %s", info);
        glDeleteProgram(program_);
        program_ = 0;
        return false;
    }
    uMvp_ = glGetUniformLocation(program_, "uMvp");
    uTex_ = glGetUniformLocation(program_, "uTex");
    return true;
}

void GlesRenderer::querySurfaceSize() {
    EGLint w = 0;
    EGLint h = 0;
    eglQuerySurface(display_, surface_, EGL_WIDTH, &w);
    eglQuerySurface(display_, surface_, EGL_HEIGHT, &h);
    width_ = w;
    height_ = h;
}

void GlesRenderer::onSurfaceResized(int32_t /*width*/, int32_t /*height*/) {
    if (ready_) querySurfaceSize();
}

bool GlesRenderer::uploadTexture(int32_t textureId, int32_t width, int32_t height,
                                 const uint8_t* pixels, bool nearest) {
    if (!ready_ || pixels == nullptr || width <= 0 || height <= 0) return false;
    releaseTexture(textureId);

    GLuint handle = 0;
    glGenTextures(1, &handle);
    glBindTexture(GL_TEXTURE_2D, handle);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    // 픽셀아트는 NEAREST(도트 유지), 회전하는 벡터풍 스프라이트는 LINEAR.
    const GLint filter = nearest ? GL_NEAREST : GL_LINEAR;
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_2D, 0);

    textures_[textureId] = handle;
    BC_LOGI("텍스처 업로드 완료 id=%d %dx%d (GLES)", textureId, width, height);
    return true;
}

void GlesRenderer::releaseTexture(int32_t textureId) {
    auto it = textures_.find(textureId);
    if (it == textures_.end()) return;
    glDeleteTextures(1, &it->second);
    textures_.erase(it);
}

void GlesRenderer::renderFrame(float clearR, float clearG, float clearB, const float* sprites,
                               int32_t spriteCount, const int32_t* runs, int32_t runCount,
                               int32_t opaqueCount) {
    if (!ready_) return;

    glViewport(0, 0, width_, height_);
    glClearColor(clearR, clearG, clearB, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    const int32_t quadCount = spriteCount > kMaxQuads ? kMaxQuads : spriteCount;
    if (quadCount <= 0 || runCount <= 0) {
        eglSwapBuffers(display_, surface_);
        return;
    }

    for (int32_t i = 0; i < quadCount; ++i) {
        const float* s = sprites + static_cast<size_t>(i) * kFloatsPerSprite;
        const float x = s[0];
        const float y = s[1];
        const float w = s[2];
        const float h = s[3];
        const float px = x + s[8] * w;
        const float py = y + s[9] * h;
        const float rot = s[10];

        float cornerX[4] = {x, x + w, x + w, x};
        float cornerY[4] = {y, y, y + h, y + h};
        if (rot != 0.0f) {
            const float c = std::cos(rot);
            const float sn = std::sin(rot);
            for (int k = 0; k < 4; ++k) {
                const float dx = cornerX[k] - px;
                const float dy = cornerY[k] - py;
                cornerX[k] = px + dx * c - dy * sn;
                cornerY[k] = py + dx * sn + dy * c;
            }
        }
        const float cornerU[4] = {s[4], s[6], s[6], s[4]};
        const float cornerV[4] = {s[5], s[5], s[7], s[7]};

        float* out = vertices_.data() +
                     static_cast<size_t>(i) * kVerticesPerQuad * kFloatsPerVertex;
        for (int k = 0; k < 4; ++k) {
            out[k * kFloatsPerVertex + 0] = cornerX[k];
            out[k * kFloatsPerVertex + 1] = cornerY[k];
            out[k * kFloatsPerVertex + 2] = cornerU[k];
            out[k * kFloatsPerVertex + 3] = cornerV[k];
            out[k * kFloatsPerVertex + 4] = s[12];
            out[k * kFloatsPerVertex + 5] = s[13];
            out[k * kFloatsPerVertex + 6] = s[14];
            out[k * kFloatsPerVertex + 7] = s[15];
        }
    }

    glUseProgram(program_);
    glBindVertexArray(vao_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glBufferSubData(GL_ARRAY_BUFFER, 0,
                    static_cast<GLsizeiptr>(quadCount) * kVerticesPerQuad * kFloatsPerVertex *
                            sizeof(float),
                    vertices_.data());

    // 화면 픽셀 좌표(좌상단 원점) -> GL NDC(좌하단 원점) 이므로 y 를 뒤집는다.
    const float sw = static_cast<float>(width_);
    const float sh = static_cast<float>(height_);
    const float mvp[16] = {2.0f / sw, 0.0f,       0.0f, 0.0f,
                           0.0f,      -2.0f / sh, 0.0f, 0.0f,
                           0.0f,      0.0f,       1.0f, 0.0f,
                           -1.0f,     1.0f,       0.0f, 1.0f};
    glUniformMatrix4fv(uMvp_, 1, GL_FALSE, mvp);
    glUniform1i(uTex_, 0);
    glActiveTexture(GL_TEXTURE0);

    int32_t drawn = 0;
    bool blendEnabled = true;
    for (int32_t r = 0; r < runCount && drawn < quadCount; ++r) {
        const int32_t texId = runs[r * 2 + 0];
        int32_t count = runs[r * 2 + 1];
        if (drawn + count > quadCount) count = quadCount - drawn;
        if (count <= 0) continue;

        // 지형은 불투명하므로 블렌딩을 꺼서 fill rate 를 아낀다.
        const bool wantBlend = drawn >= opaqueCount;
        if (wantBlend != blendEnabled) {
            if (wantBlend) glEnable(GL_BLEND); else glDisable(GL_BLEND);
            blendEnabled = wantBlend;
        }

        auto it = textures_.find(texId);
        if (it != textures_.end()) {
            glBindTexture(GL_TEXTURE_2D, it->second);
            const auto offset = static_cast<size_t>(drawn) * kIndicesPerQuad * sizeof(uint16_t);
            glDrawElements(GL_TRIANGLES, count * kIndicesPerQuad, GL_UNSIGNED_SHORT,
                           reinterpret_cast<void*>(offset));
        }
        drawn += count;
    }

    if (!blendEnabled) glEnable(GL_BLEND);
    glBindVertexArray(0);
    eglSwapBuffers(display_, surface_);
}

void GlesRenderer::onSurfaceDestroyed() {
    if (display_ == EGL_NO_DISPLAY) return;

    eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (context_ != EGL_NO_CONTEXT) {
        eglDestroyContext(display_, context_);
        context_ = EGL_NO_CONTEXT;
    }
    if (surface_ != EGL_NO_SURFACE) {
        eglDestroySurface(display_, surface_);
        surface_ = EGL_NO_SURFACE;
    }
    eglTerminate(display_);
    display_ = EGL_NO_DISPLAY;

    textures_.clear();
    program_ = 0;
    vao_ = 0;
    vbo_ = 0;
    ibo_ = 0;
    ready_ = false;
}

}  // namespace bc::gl
