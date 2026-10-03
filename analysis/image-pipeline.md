# Samsung Image Pipeline — 실제 이미지 데이터가 처리되는 위치

대상: `com.sec.android.app.camera` 16.5.00.55
범례: **[확인됨]** = 코드나 바이너리 문자열로 확인 / **[추정]** = 구조상 가능성이 높음 / **[확인 필요]** = 근거 부족

---

## 1. 파이프라인 전체 그림

```
 Sensor
   │  (APK 제어: CaptureRequest + Samsung vendor key만 전달)
   ▼
 HAL / ISP (Samsung Unihal camera provider)  ← APK 외부
   │  3A(AE/AF/AWB), demosaic, CCM, ISP NR/sharpen, GTM, 장면 판단(dsMode), JPEG(경로 A)
   │  메타데이터 출력: dynamicShotHint, globalToneMap, drcRatio, colorTemperature,
   │                  captureEv, captureTotalGain, sceneDetectionInfo, faceToneWeight ...
   ▼
 ImageReader (JPEG | JPEG_R | YUV_420_888 | YCBCR_P010 | RAW10/12/SENSOR)
   ▼
 ┌──────────────────────── APK core2 Node Chain (IPP / PPP) ─────────────────────────┐
 │ PREPROCESSOR   : SecCompressedRawDecoderNode, ImageResizeNode,                     │
 │                  SaveYuvForGainMapNode, SaveYuvForDualCameraNode                   │
 │ MULTI_FRAME    : dsMode별 1개 (MfHdr / LlHdr / HifiLls / SuperNight / AiIsp /      │
 │                  AiHighRes / SR / TetraSR / HybridHdr / AebHdr / SIE …)            │
 │                  ※ RAW 입력 노드는 HAL의 globalToneMap(double[])을 받아 RAW→YUV 렌더 │
 │ SINGLE_FRAME   : SINGLE_UDC → LOCAL_TM(SecLocaltmNode) → FACE_RESTORATION           │
 │                  → BEAUTY(SecBeautyNode v4) → UW/WIDE_DISTORTION → SELFIE_CORRECTION │
 │                  → SINGLE/DUAL_BOKEH → STEREO_PHOTO                                 │
 │                  → FILTER_NOT_SUPPORTING_NON_D (SecFilterNode: COLOR_TUNE, FOOD)    │
 │                  → NON_DESTRUCTION (원본 보존 인코딩)                                │
 │                  → FILTER_SUPPORTING_NON_D (SecFilterNode: BASIC, USER_GENERATED)   │
 │                  → WATERMARK                                                        │
 │ ENCODER        : SecImageCodecNode(v2) + SefNode  → JPEG / HEIF / (gain map)         │
 └────────────────────────────────────────────────────────────────────────────────────┘
   ▼
 PictureCallback → PictureProcessor.PictureSavingTask → MediaStore (DatabaseUtil.insertToDb)
```

근거: `IppNodeController.java:28-31,54-67`, `PppNodeController.java:294`, `NodeChainConfiguration.java:195-290`, `NodeChainKeyContainer.java:210-375` **[확인됨]**

---

## 2. 질문별 답변 (3단계)

### A. Sensor → ISP: APK가 직접 제어하는 부분과 HAL에 맡기는 부분

| 구분 | APK가 직접 하는 것 | HAL/ISP에 맡기는 것 |
|---|---|---|
| 노출/AF | `CaptureRequest` 표준 키와 벤더 키(`aeExtraMode`, `meteringMode`, `multiFrameEvList`, `superNightShotMode`, `exposureTable`, `afSpeed` …) **값 전달** | 실제 AE/AF/AWB 알고리즘 |
| 장면 판단 | 프리뷰에 `SribSceneDetectionNode`(libSceneDetectorJNI) 실행 → 결과를 `samsung.android.control.sceneDetectionInfo`로 HAL에 전달 | 장면별 ISP 튜닝, dsMode 결정(`dynamicShotHint`) |
| ISP 처리 | **없음** | demosaic, CCM, lens shading, ISP NR, edge/sharpen, GTM/LTM, JPEG |
| 요청 키 | `edge.mode`, `control.colorTemperature`, `control.wbLevel`, `control.colorSpaceMode`, `control.specialImageQualityPolicy`, `control.personalPresetIndex` | 키 해석 |

근거: `SemCaptureRequest.java` 벤더 키 156개 문자열 **[확인됨]**. HAL이 각 키를 어떻게 해석하는지는 **[확인 필요]**

### B. RAW/Bayer → YUV/RGB: APK에서 직접 하는가?

- **APK Java 코드에는 demosaic 구현이 없습니다.** **[확인됨: 관련 코드 없음]**
- RAW를 입력으로 받는 노드가 native 쪽에서 RAW→YUV 렌더링을 합니다. 모두 vendor `.so`입니다.
  - `SecAiIspNode` (`libAIQSolution_MPI*.camera.samsung.so` 계열 추정) — Bayer AI ISP
  - `MpiSuperNightNode` v2 (`libMultiFrameProcessing20*.camera.samsung.so` 추정), `ArcSuperNightNode` v3 (`libsupernight_wrapper_v3`)
  - `SecTetraSrNode`, `SecHexadecaSrNode`, `ArcSRRawNode`, `ArcMacroRawSrNode`, `ArcAiClearZoomNode`, `SecAiHighResNode`
  - `ProRgbConversionNode` (Expert RAW / `pro_single_rgb`)
- 이 노드들은 HAL이 프레임마다 보내는 **`samsung.android.control.globalToneMap`(double[])** 을 Java에서 꺼내 `NATIVE_COMMAND_SET_GLOBAL_TONE_MAP`으로 native에 넘깁니다 (`SecAiIspNode.java:326`, `MpiSuperNightNode.java:445`, `SecTetraSrNode.java:197`, `SecHexadecaSrNode.java:191`, `SecAiHighResNodeBase`, `ArcAiClearZoomNode` v2, `ArcMacroRawSrNode`). **[확인됨]**
  → **RAW 경로의 global tone curve가 Java를 거쳐 흐릅니다. 매우 중요한 개입 지점입니다.**
- DNG 저장: `DngManageNode` (Java + native) **[확인됨]**

### C. Multi-frame

| 기능 | dsMode | Java 노드(구현) | native 라이브러리(dlopen, libnode-jni 문자열 기준) | 입력 | 확실성 |
|---|---|---|---|---|---|
| HDR (주간 MF) | 20-29 | `MfHdrNodeBase` → `ArcMfHdrNode` v1/v2/v4 | `libhigh_dynamic_range.arcsoft.so` | YUV burst | 확인됨(노드·lib명), lib 매핑은 추정 |
| 저조도 HDR | 30-39,60 | `LlHdrNodeBase` → `ArcLlHdrNode` v1/v4 | `liblow_light_hdr.arcsoft.so` | YUV burst | 동일 |
| HiFi LLS / Multi-frame NR | 10-15 | `MpiHifiLlsNode` | `libMultiFrameProcessing10/20.camera.samsung.so` | YUV burst | 동일 |
| Night | 80-88, 260-269, 320-321 | `ArcSuperNightNode` v3, `MpiSuperNightNode` v2 | `libsupernight_wrapper_v3.camera.samsung.so`, MPI | RAW 또는 YUV (`SET_YUV_INPUT_DATA` 존재) | 확인됨 |
| AI ISP (Bayer NN) | 334-342 | `SecAiIspNode` | `libAIQSolution_MPI*.camera.samsung.so` (추정) | RAW | 확인됨(노드), lib 추정 |
| High Resolution | 90,91 / 211,212 | `ArcHighResNode`, `ArcFusionHighResNode` | `libhigh_res.arcsoft.so` | YUV | 동일 |
| AI High-Res | 180-186, 310-312 | `SecAiHighResNode` v1-v3 | `libAIHRWrapper.camera.samsung.so`, `libHREnhancementAPI.camera.samsung.so` | RAW/YUV | 동일 |
| Super Resolution | 70-75, 130, 162, 290-294, 360-361 | `ArcSRNode`, `ArcUwSRNode`, `ArcSRRawNode`, `SecTetraSrNode`, `SecHexadecaSrNode` | `libsuperresolution_wrapper_v2`, `libsuperresolutionraw_wrapper_v2`, `libuwsuperresolution_wrapper_v1`, `libdtsr_wrapper_v1`, `libhexadecasr_wrapper_v1` | | 동일 |
| Hybrid / AEB / SS HDR | 191-243 / 200 / 150 | `ArcHybridHdrNode`, `ArcAebHdrNode`, `ArcSsHdrNode` | `libhybridHDR_wrapper`, `libAEBHDR_wrapper`, `libsame_source_hdr.arcsoft.so` | | 동일 |
| Burst | (BURST_CAPTURE) | BurstCaptureController / Maker burst | - | JPEG | 확인됨(별도 경로) |

### D. Image enhancement (톤·디테일·색)

| 처리 | 수행 위치 | 근거 | 확실성 |
|---|---|---|---|
| Global tone mapping | **HAL GTM** (`globalToneMap` 메타) → RAW 노드에서 재사용 | 7개 노드의 `setGlobalToneMap` | 확인됨 |
| Local tone mapping / local contrast | `SecLocaltmNode` (`libLocalTM_wrapper.camera.samsung.so`) — `drcRatio`, `captureTotalGain`, `captureEv`, `colorTemperature`, faces, `sceneIndex`, `personalPresetIndex`, `sunDetectionInfo` 입력 | `SecLocaltmNode.createLocaltmInitParam` (:72) | 확인됨 |
| Highlight recovery / shadow | 멀티프레임 HDR 노드 + LocalTM + HAL GTM | 노드 구조 | 추정 |
| Denoise | ISP NR(HAL) + 멀티프레임 merge(MFHDR/LLHDR/HiFi) + AI 노드 (`setNoiseIndex` in ArcSRRaw/ArcAiClearZoom/MacroRawSr) | libnode-jni 심볼 | 확인됨(심볼), 강도 파라미터는 확인 필요 |
| Sharpening / edge | ISP(`edge.mode` 요청 키) + ArcSoft 노드 내부(`sharpenIntensity`, `ARC_SR sharpIntensity`) | libnode-jni 문자열 `ARC_*HDR_GetDefaultParam … saturation = %d, sharpenIntensity = %d` | 확인됨(파라미터 존재) |
| Saturation (HDR 노드 내부) | ArcSoft LLHDR/SSHDR/HRLLHDR `GetDefaultParam`의 `intensity / lightIntensity / saturation / sharpenIntensity` | 동일 | 확인됨 — **기본값을 쓰는지, 덮어쓰는지는 확인 필요** |
| Skin tone | `SecBeautyNode` v4(`libBeauty_v4`), Selfie Tone(`personalPresetIndex` → HAL + LocalTM), `faceToneWeight` 결과 메타, `beautyFaceSkinColor` 요청 키 | `AbstractShootingModePresenter.setSelfieToneMode`, `MakerSettingApplier.setSelfieToneMode` | 확인됨 |
| Face restoration | `ArcFaceRestoNode` (`libFaceRestoration.camera.samsung.so`) | dsExtraInfo `NEED_FACE_RESTORATION` | 확인됨 |
| Color correction (사용자) | `SecFilterNode` COLOR_TUNE: `customcolor,TE,TI,CO,SA,HL,SL` | `ColorTuneProcessor`, `EffectController.getColorTuneParameterString` | 확인됨 |
| LUT 필터 | `SecFilterNode` BASIC/USER_GENERATED → `SemFilterBufferedProcessor` (framework) / `libMyFilter*.so` / filterprovider LUT | `FilterProcessor.java:144,210` | 확인됨(호출), 엔진 내부는 확인 필요 |
| Social Image Enhance | `ArcSIENode` (`libimage_enhancement.arcsoft.so`, `ARC_IE_*`) | libnode-jni 문자열 | 확인됨 |
| 장면 인식 | 프리뷰: `SribSceneDetectionNode` (libSceneDetectorJNI + `libSceneDetector_v1`) → sceneIndex | `ProcessingPhotoMakerBase.initializeSequence:720` → `ExtraBundle.f6012l` | 확인됨 |
| 의미 분할(semantic map) | dsExtraInfo `NEED_SEMANTIC_MAP`(0x100) 플래그 존재 | `DynamicShotExtraInfo` | 확인됨(플래그), 사용처는 확인 필요 |

### E. Final image — JPEG/HEIF 직전 지점 (가장 중요)

경로 B/C에서 인코딩 직전 순서는 다음과 같습니다. **[확인됨]**

```
… → LOCAL_TM → … → FILTER_NOT_SUPPORTING_NON_D → NON_DESTRUCTION → FILTER_SUPPORTING_NON_D → WATERMARK → ENCODER
                                                                                                 └ SecImageCodecNode.encode()
```

| 직전 단계 | 파일:라인 | 버퍼 접근 | 비고 |
|---|---|---|---|
| `SecFilterNode.processPictureYuv` | node/filter/SecFilterNode.java:172 | **Java에서 `byte[]`로 전체 YUV 복사 → 처리 → 새 ImageBuffer** | 필터가 켜졌을 때만 동작 |
| `WatermarkNode` | node/watermark/ | 워터마크 합성(GL, libpost_processor_jni) | 워터마크 켰을 때만 |
| `SecImageCodecNodeBase.processPictureYuv` → `processPictureInternal` | node/imageCodec/samsung/SecImageCodecNodeBase.java:406,267 | ImageBuffer(direct) → native encode | **항상 동작 (경로 B/C)** |
| `SecImageCodecNode.encode` | node/imageCodec/samsung/v2/SecImageCodecNode.java:135 | `NATIVE_COMMAND_IMAGE_CODEC_SET_CONFIGURATION`, `SET_MAIN_IMAGE`(101), `SET_SUB_IMAGE_FOR_GAIN_MAP`(102) | gain map(Ultra HDR) 처리 포함 |

**경로 A(HAL JPEG)에는 이 단계가 없습니다.** HAL이 만든 JPEG가 `PhotoMakerBase` PictureCallback(:381)에서 `sendPictureTakenCallback`으로 바로 앱에 전달됩니다. **[확인됨]**

---

## 3. 체인 활성 조건 (NodeChainConfiguration.java) [확인됨]

| 체인 | 활성 조건 |
|---|---|
| PREPROCESSOR | `ExtraBundle.PREPROCESSING_OPTION` 비트 (`SAVE_YUV_FOR_GAIN_MAP`는 **필터/뷰티/보케/스테레오가 켜지면 해제**) |
| MULTI_FRAME | `NodeChainKeyContainer.getNodeChainInfo(dsMode)` ≠ SINGLE |
| LOCAL_TM | `dsExtraInfo & NEED_LTM(2)` (듀얼 보케일 때는 보케 노드가 LTM을 포함) |
| FACE_RESTORATION | `dsExtraInfo & 32` |
| BEAUTY | `isBeautyNodeChainRequired()` (MakerPrivateKey 뷰티 모드) |
| FILTER_NOT_SUPPORTING_NON_D | FilterMode ∈ {COLOR_TUNE, FOOD} |
| FILTER_SUPPORTING_NON_D | FilterMode ∈ {BASIC, USER_GENERATED} |
| WATERMARK | 워터마크 설정 |
| ENCODER | resultFormat ∈ {JPEG, JPEG_R, HEIC, HEIC_ULTRAHDR} |

**주의 (부작용)** — `AutoPhotoMaker.getDsExtraInfo` (:326):
```java
if (isFilterEnabled()) dsExtraInfo = (dsExtraInfo | 0x100000 /*FILTER*/) & ~0x2 /*NEED_LTM*/;
```
→ **필터를 켜면 앱 측 LocalTM 노드가 꺼지고**, gain map용 YUV 저장도 해제됩니다. 기존 COLOR_TUNE 필터를 그대로 프로토타입에 쓰면 색만 바뀌는 게 아니라 **LTM·HDR gain map까지 바뀝니다.** (HAL이 FILTER 비트를 받았을 때 LTM을 대신 하는지는 **[확인 필요]**)

---

## 4. 이미지 버퍼 형식

| 포맷 | 사용 경로 | 근거 |
|---|---|---|
| JPEG / JPEG_R | HAL 인코딩(경로 A), Draft | `SemImageFormat` switch, ProcessingPhotoMakerBase:112-136 |
| YUV_420_888 → packed NV21 | 8-bit 처리 | `ImageUtils.convertFlexibleYuv420888ToPackedNV21` (libimageutils-jni) |
| YCBCR_P010 | 10-bit HDR(JPEG_R/HEIC_ULTRAHDR) 경로 | `BeautyVideoMaker.java:640` 등 `JPEG_R ? YCBCR_P010 : YUV_420_888` |
| RAW_SENSOR / RAW10 / RAW12 | RAW 멀티프레임 | onPictureTaken switch |
| stride | `StrideInfo(rowStride, heightSlice)` | `SecFilterNode.processImage`, `CodecConfiguration.rowStride/heightSlice` |

---

## 중간 결과

### Confirmed
- 주요 영상처리는 HAL/ISP와 vendor native `.so`에서 합니다. APK Java는 **체인 구성, 파라미터 전달, 메타데이터 중계**를 맡습니다.
- 인코딩 직전 Java 접근 지점: `SecFilterNode`(조건부), `SecImageCodecNode`(항상, 경로 B/C).
- RAW 렌더 노드들의 tone curve(`globalToneMap`)가 Java를 거쳐 전달됩니다.
- 필터를 켜면 LTM과 gain map 저장이 꺼지는 부작용이 있습니다.

### Likely
- ArcSoft HDR 노드의 `saturation/sharpenIntensity` 기본값은 vendor 라이브러리 `GetDefaultParam`에서 오고, 일부를 libnode-jni가 덮어쓸 가능성이 있습니다.
- 주간 SINGLE 촬영의 look은 대부분 HAL ISP 튜닝으로 정해집니다.

### Unknown
- `globalToneMap` double[]의 형식(제어점 개수, 입력/출력 정규화) — 로그로 길이와 값 확인 필요.
- `SemFilterBufferedProcessor`(capture 필터 엔진)의 정확한 위치 — APK에 없으므로 framework jar로 추정.
- HAL이 `FILTER`, `EXTRA_POST_PROCESS` 비트를 받았을 때 ISP 출력이 어떻게 달라지는지.

### Next Targets
- `SecLocaltmNode` native 파라미터(`setLocalTmAuxParam`, `setLocalTmFwkParam`)와 `personalizeParams` 의미.
- `ArcLlHdrNode` v4 / `ArcMfHdrNode` v4의 `GetDefaultParam` 이후 덮어쓰기 여부(libnode-jni 디스어셈블 범위 제한).
- `globalToneMap` 실측 로그.
