#version 450

layout(set = 0, binding = 0) uniform sampler2D uTex;

layout(location = 0) in vec2 vUV;
layout(location = 1) in vec4 vColor;

layout(location = 0) out vec4 outColor;

void main() {
    // discard 는 타일 기반 GPU 에서 early-Z 를 무력화한다.
    // 알파 블렌딩이 이미 투명 픽셀을 처리하므로 쓰지 않는다.
    outColor = texture(uTex, vUV) * vColor;
}
