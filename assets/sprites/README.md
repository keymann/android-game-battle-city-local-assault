# Sprite resources

## Runtime-ready files

- `terrain/components_atlas_128.png`: 8×6, 1024×768, 48 sprites
- `tanks/components_atlas_128.png`: 4×6, 512×768, 24 sprites
- `effects/components_atlas_128.png`: 8×4, 1024×512, 32 sprites
- 대응하는 `components_atlas.json`: 이름, 0-based index, row, column, 128×128 셀 크기
- 각 `components/` 폴더: 이름으로 분리된 개별 RGBA PNG

## Direction convention

탱크 및 방향성 발사체는 `up → right → down → left` 순서입니다.

## Terrain variation grouping

- Ground: 8
- Brick: 8
- Steel: 4
- Water animation: 4
- Forest: 4
- Ice: 4
- Base state: 8
- Environment objects: 8

동일 타입 variation은 충돌/게임플레이 데이터는 공유하고 렌더링 인덱스만 선택하는 방식이 적합합니다. 맵의 좌표 해시를 seed로 사용하면 네트워크 전송 없이 모든 클라이언트에서 동일한 variation을 결정할 수 있습니다.

## Import notes

- 텍스처 필터는 nearest-neighbor를 사용합니다.
- 아틀라스 셀은 128×128이며 좌상단이 원점입니다.
- 이동·폭발 프레임은 셀 중앙을 기본 anchor로 사용합니다.
- `*_raw.png`는 생성 원본이므로 런타임 리소스로 사용하지 않습니다.
