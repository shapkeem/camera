# 자체 카메라 앱 계획 (Galaxy Z Flip7)

## 목표
- 삼성이 공식으로 열어 준 처리(Camera Extensions: 야간, HDR, 인물, 얼굴 보정)는 그대로 받는다.
- **인코딩 직전 YUV/비트맵 단계**에 우리 tone/색감 처리를 넣는다. `tuning-prototype.md`에서 설계한 "ENCODER 직전 개입"을 공개 API만으로 재현하는 것이 목표다.
- 삼성 카메라 앱은 수정하지 않는다. 기본 카메라와 함께 설치해서 쓴다.

## 기반
- **Open Camera** (GPL v3, Java, Camera2, vendor extension 지원)를 fork한다.
- 패키지명을 바꿔서 원본 Open Camera, 기본 카메라와 따로 설치되게 한다 (예: `com.shapkeem.camera`).
- 원저작자 표기와 GPL 라이선스를 유지한다. 개인 사용은 문제없고, 배포할 경우 소스를 공개해야 한다.
- 언어: 기존 코드가 Java라서 수정 부분도 Java로 맞춘다. 새 화면(진단 등)은 필요하면 Kotlin으로 작성한다.
- 위치: 이 저장소의 `app/` 폴더.

## 단계

### 0단계: 환경 준비
| 작업 | 내용 |
|---|---|
| 소스 가져오기 | Open Camera 공식 저장소(SourceForge git)의 최신 릴리스 태그 |
| 빌드 환경 | 컨테이너에 Android SDK/Gradle 설치 → debug APK 빌드 확인 |
| 배포 경로 | GitHub Actions로 push마다 debug APK 빌드 → Actions artifact로 다운로드 |
| 패키지명/앱 이름 변경 | 기존 앱과 충돌 없이 설치 |

**완료 기준**: 플립7에 설치되고, 수정 없는 상태로 촬영과 저장이 정상 동작.

### 1단계: 기기 기능 진단 화면
한 번 실행하면 아래 항목을 화면에 표시하고 텍스트로 공유(복사)할 수 있게 한다.

| 항목 | API |
|---|---|
| 카메라 목록, 물리/논리 카메라 | `CameraManager.getCameraIdList`, `getPhysicalCameraIds` |
| Extension 지원 모드 | `CameraExtensionCharacteristics.getSupportedExtensions` |
| Extension별 출력 형식/크기 (JPEG, YUV_420_888, JPEG_R) | `getExtensionSupportedSizes(ext, format)` |
| Extension 지연 시간 추정 | `getEstimatedCaptureLatencyRangeMillis` |
| Extension 동작 중 지원 컨트롤 (줌, 초점 등) | `getAvailableCaptureRequestKeys` |
| 일반 모드 출력 형식 (HEIC, JPEG_R, RAW, P010) | `SCALER_STREAM_CONFIGURATION_MAP` |
| 10-bit / HDR 프로필 | `REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES` |
| 50MP 풀해상도 모드 (메인 50MP 센서는 평소 12.5MP로 합쳐서 출력) | `SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION`, `SENSOR_PIXEL_MODE` |
| 동시 카메라 (듀얼 레코딩 가능 여부) | `getConcurrentCameraIds` |
| 고속 촬영 FPS | `getHighSpeedVideoFpsRanges` |
| 손떨림 보정 모드 | `CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES` |
| 삼성 vendor tag 노출 여부 | `CameraCharacteristics.getKeys()` 중 `samsung.android.*` 전체 목록 |

**완료 기준**: 플립7 진단 결과를 받아서 이 문서의 "확인 필요" 항목을 확정.

#### 50MP 확보 경로 (위에서부터 순서대로 시도)
APK 분석에서 삼성 앱이 쓰는 고해상도 관련 vendor tag를 확인했다. 진단 화면에서 이 키들이 서드파티 앱에 보이는지 조회한다.

| 순서 | 경로 | 비고 |
|---|---|---|
| 1 | Android 표준 최대 해상도 모드 (`SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION`) | 열려 있으면 가장 깔끔함 |
| 2 | 삼성 vendor tag를 공개 Camera2 API(`CaptureRequest.Key(name, type)`)로 지정 | 후보: `samsung.android.sensor.pixelMode`, `samsung.android.control.highresModeInfo`, `samsung.android.scaler.availableHighresYuvStreamConfigurations`, `availableHighresRawStreamConfigurations`, `availableRemosaicCropCapabilities`, `highresResultSize`. HAL이 기본 앱에만 허용할 수 있음 → 확인 필요 |
| 3 | Expert RAW로 50MP DNG 촬영 → 우리 앱에서 렌더링 | APK에 `availableExpertRawHighresRawStreamConfigurations`가 있어서 Expert RAW 고해상도 경로가 존재함. 플립7 지원 여부는 확인 필요 |
| 4 | 12.5MP 연사 여러 장 → 멀티프레임 초해상도 합성 | 항상 가능. 실제 디테일 증가는 제한적 |

루팅, 시스템 영역 변경, 삼성 앱 수정은 하지 않는다.

### 2단계: 처리 파이프라인 삽입 (첫 프로토타입)
`tuning-prototype.md`의 설계를 그대로 옮긴다.

| 항목 | 내용 |
|---|---|
| 삽입 위치 | Open Camera의 이미지 저장 경로 (`ImageSaver`). 정확한 메서드는 소스를 받은 뒤 확정 |
| 입력 | Extension이 YUV_420_888 출력을 지원하면 YUV, 아니면 JPEG 디코드 후 비트맵 |
| 처리 | Y(또는 밝기)만 ×1.10 LUT, 색(UV)은 그대로. full/limited range 구분 |
| 플래그 | `CUSTOM_TUNING_ENABLED`, `AB_DUMP_ENABLED` — 설정 화면 토글, 기본값 꺼짐 |
| A/B 비교 | 켜면 처리 전/후 이미지를 같이 저장 |
| 계측 | 처리 시간을 로그와 화면 토스트로 표시 |
| 실패 처리 | 처리 중 예외가 나면 원본을 그대로 저장 |

**완료 기준**: 플립7에서 야간/HDR Extension으로 찍은 사진에 처리가 적용되고, A/B 비교로 Y만 바뀐 것을 확인. 처리 시간 측정.

### 3단계: 저장 형식
- HEIF 저장 (`HeifWriter` 또는 카메라 HEIC 출력)
- Ultra HDR (`JPEG_R` 출력 또는 Gainmap API)
- EXIF/방향/위치 정보 유지 확인

### 4단계: 색감 엔진
- 고정 LUT → **3D LUT(.cube) 불러오기**
- tone curve (하이라이트 롤오프, 암부)
- 프리셋 저장/선택 ("내 필터" 대체)
- Expert RAW DNG 입력 경로 (삼성 처리 유지 + 우리 렌더링)

### 5단계: 추가 기능 (진단 결과에 따라)
- 모션 포토 (셔터 전 영상 링버퍼 + 사진 합성)
- 음식 모드, 구도 가이드, 자동 FPS
- 동시 카메라가 열려 있으면 듀얼 레코딩

## 역할 분담
| 제가 하는 것 | 사용자께서 해 주실 것 |
|---|---|
| 코드 작성, 빌드, GitHub 업로드 | APK 다운로드 후 플립7에 설치 ("출처를 알 수 없는 앱" 허용) |
| 진단 결과 분석, 다음 단계 반영 | 진단 화면 결과 복사해서 전달 |
| 처리 로직 수정 | 촬영 테스트 후 A/B 사진 또는 느낌 전달 |

저는 실기기가 없어서, **각 단계의 완료는 사용자께서 플립7로 확인하신 뒤에 확정**합니다.

## 위험 요소와 대응
| 위험 | 대응 |
|---|---|
| Extension 모드에서 YUV 출력이 안 됨 | JPEG 디코드 → 처리 → 재인코딩 (화질 손실 최소 설정) |
| Extension 촬영이 느림 (야간은 수 초) | 구조상 피할 수 없음. 일반 모드는 ZSL로 개선 |
| 처리 시간이 길어 저장 지연 | 백그라운드 저장 큐 (Open Camera 기존 구조 활용) |
| Open Camera 업데이트 반영 | 수정 부분을 별도 클래스로 분리해서 병합을 쉽게 유지 |
| 플립7이 일부 기능을 막아 둠 | 1단계 진단으로 먼저 확정하고 계획 조정 |

## 하지 않는 것
- 삼성 카메라 APK 수정, 재서명, 시스템 영역 변경
- 삼성 내부 라이브러리(.so)나 디컴파일 코드 재사용
