# Galaxy Z Flip7 진단 결과 (1단계)

기기: SM-F766N (b7s), Android 16 / SDK 36, 빌드 BP4A.251205.006.F766NKSSCBZH3, 보안 패치 2026-08-05.
Tune Camera 진단 화면(`DiagnosticsActivity`)으로 수집. 서드파티 앱 기준 결과다.

## 카메라 구성
| ID | 방향 | 정체 (추정 포함) | 최대 출력 | 비고 |
|---|---|---|---|---|
| 0 | 후면 | **논리 카메라** (물리 2 + 5), 줌 0.58~10x | 12.5MP | 기본으로 쓸 카메라. FULL 레벨 |
| 2 | 후면 | 초광각 (1.7mm) | 12.0MP | LIMITED |
| 5 | 후면 | 메인 광각 물리 카메라 (5.4mm, 8.16x6.12mm 센서). **목록에 없음** | 12.5MP | 0의 물리 카메라. HDR/NIGHT Extension 없음 |
| 1 | 전면 | 10MP 셀피 | 10.0MP | |
| 3 | 전면 | 6.3MP (3008x2080) | 6.3MP | 같은 전면 센서의 다른 모드로 추정 (확인 필요) |

- 동시 카메라: `{0,1}`, `{0,3}` → **후면+전면 동시 촬영(듀얼 레코딩) 가능**.

## Extension (삼성 처리)
| 모드 | 지원 | 출력 형식 | 비고 |
|---|---|---|---|
| NIGHT | ✅ (0,1,2,3) | JPEG, **YUV_420_888**, **JPEG_R** — 최대 해상도 | postview 지원 (촬영 직후 미리보기) |
| BOKEH | ✅ | JPEG, YUV, JPEG_R | |
| FACE_RETOUCH | ✅ | JPEG, YUV, JPEG_R | |
| **HDR** | ❌ | — | 서드파티에 열려 있지 않음 |
| **AUTO** | ❌ | — | |

- 예상 지연 시간: HAL이 값을 주지 않음 (`unknown`). 실측 필요.
- 모든 Extension의 요청 키에 **`tonemap.curve`** 와 **`extension.strength`** 가 있다.
  - `tonemap.curve`: Extension 처리 안에서 우리가 톤 커브를 지정할 수 있다는 뜻. 실제 반영 여부는 촬영으로 확인 필요.
  - `extension.strength`: 야간/보케/보정 강도 조절.

## 해상도 (50MP)
- `SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION` = null, 최대 해상도 스트림 맵 없음, `ULTRA_HIGH_RESOLUTION_SENSOR` capability 없음.
  → **표준 경로로는 50MP 불가. 12.5MP가 상한.**
- 삼성 vendor tag: `getKeys()` 목록에는 0개(서드파티에 숨김). 하지만 **이름으로 직접 읽으면 값이 나오는 키가 있다**.
  - `samsung.android.scaler.availableRemosaicCropCapabilities` = `[0,1,2000, 1,1,2000, 3,1,2000]` (카메라 0, 5)
  - 3개씩 묶인 구조로 보인다. `2000`은 2.0x 줌일 가능성이 높다. 즉 **2x에서 50MP 센서 중앙을 잘라 쓰는 remosaic crop**("2배 광학 수준")이 HAL에 있다는 뜻으로 추정 (확인 필요).
  - 나머지 고해상도 스트림 키는 null.
- 결론: 50MP 전체 촬영은 막혀 있을 가능성이 크다. 다음에 시도할 것:
  1. 2x 줌에서 촬영한 결과가 디지털 크롭인지 remosaic(선명)인지 비교
  2. APK 분석의 다른 vendor 키(요청 키 `samsung.android.sensor.pixelMode`, `samsung.android.control.highresModeInfo` 등)를 실제 촬영 요청에 넣어 결과 크기 확인
  3. Expert RAW 50MP DNG 경로 (삼성 앱 자체 기능)

## 그 외
| 항목 | 결과 | 의미 |
|---|---|---|
| 일반 모드 출력 | JPEG, YUV, **YCBCR_P010(10-bit)**, **JPEG_R**, RAW_SENSOR | 10-bit YUV 처리, Ultra HDR 저장, DNG 가능 |
| HEIC | ❌ (카메라 출력에 없음) | HEIF는 앱에서 하드웨어 HEVC 인코더로 만들어야 함 |
| 고속 촬영 | 후면 1080p **240fps**, 전면 120fps | 슬로우모션 240fps까지. 960fps 불가 |
| HDR 동영상 | HLG10, HDR10, HDR10+ | 10-bit HDR 동영상 가능 |
| 손떨림 보정 | OFF/ON/PREVIEW, OIS (메인) | 기본 보정 가능 |
| 줌 | 0.58x~10x (카메라 0) | 초광각~10x 디지털 |

## 계획 반영
- 2단계 처리 위치: Extension이 **YUV_420_888을 최대 해상도로 출력**하므로 원래 설계(인코딩 전 YUV 개입)가 그대로 가능하다.
  - 첫 프로토타입은 위험을 줄이려고 Open Camera의 기존 저장 경로(JPEG → 비트맵)에서 처리하고, 이후 YUV 경로로 옮긴다.
- 삼성 HDR Extension이 없으므로 HDR은 일반 모드의 HAL 기본 처리 + Open Camera HDR/NR 모드에 의존한다. 야간은 삼성 처리를 쓸 수 있다.
- `tonemap.curve`가 Extension에서도 받아지면, 톤 조정을 삼성 처리 안쪽에서 할 수 있다 → 2단계에서 실험 항목으로 추가.
