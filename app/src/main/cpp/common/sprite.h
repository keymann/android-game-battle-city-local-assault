#pragma once

#include <cstdint>

namespace bc {

// Kotlin SpriteBatch 와 반드시 동일해야 하는 레이아웃.
// 스프라이트 1개 = float 16개 (64 byte).
//
//   0 dstX      1 dstY      2 dstW      3 dstH
//   4 u0        5 v0        6 u1        7 v1
//   8 originX   9 originY  10 rotation 11 (예약)
//  12 r        13 g        14 b        15 a
//
// 텍스처 바인딩은 스프라이트마다 넣지 않고 run 배열로 분리한다.
// runs = [textureId, spriteCount, textureId, spriteCount, ...]
// Kotlin 쪽에서 (layer, textureId) 로 정렬한 뒤 채워 넣는다.
constexpr int kFloatsPerSprite = 16;

struct SpriteRun {
    int32_t textureId;
    int32_t count;
};

}  // namespace bc
