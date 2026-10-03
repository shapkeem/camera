# Capture Flow — 셔터부터 최종 JPEG/HEIF 저장까지

대상: Samsung Camera `com.sec.android.app.camera` **16.5.00.55** (arm64-v8a, DEX 4개)
분석 방식: jadx 1.5.1로 디컴파일한 뒤 **심볼/참조 검색으로 후보를 좁히고 필요한 메서드만** 읽었습니다. 클래스 전체를 읽지 않았습니다.
경로 표기: `sources/` = jadx 출력 루트. 난독화된 메서드명(예: `CamDevice.O()`)은 그대로 적었습니다.

---

## 0. APK 구조 요약 (1단계)

| 항목 | 값 | 근거 |
|---|---|---|
| package | `com.sec.android.app.camera` | AndroidManifest 문자열 |
| versionName | `16.5.00.55` | AndroidManifest 문자열 |
| DEX | `classes.dex` ~ `classes4.dex` (4개, 약 38MB) | `unzip -l` |
| ABI | `lib/arm64-v8a` 전용 | `unzip -l` |
| 앱(UI/엔진) 코드 | `com.sec.android.app.camera.*` (주로 classes4.dex) | jadx 주석 `loaded from` |
| 촬영 프레임워크 | `com.samsung.android.camera.core2.*` (classes3.dex) | jadx 주석 |
| 영상처리 노드 | `com.samsung.android.camera.core2.node.*` | 패키지 구조 |
| 노드 체인 구성 | `com.samsung.android.camera.core2.processor.nodeController.*` | 패키지 구조 |

**APK 안에 들어 있는 native .so (21개)**: `libnode-jni.so`(1.3MB, 핵심 JNI 브리지), `libcamera_effect_processor_jni.so`, `libpost_processor_jni.so`, `libSceneDetectorJNI.so`, `libimageutils-jni.so`, `libquramresizer-jni.so`, `libimagexmpinjector.so`, `libtype-converter.so`, `libdirectbuffer-jni.so`, `libnativeutils-jni.so`, `libpanorama.arcsoft.so`, `libhandgesture.arcsoft.so`, `libPanoramaInterface_arcsoft.so`, `libatomjpeg_panorama_enc.quram.so`, `librenderscript-toolkit.so`, `libc++_shared.so`, 기타.

**중요한 사실**: HDR, Night, AI ISP, LocalTM, Beauty 같은 실제 알고리즘 라이브러리(`libMultiFrameProcessing20.camera.samsung.so`, `libLocalTM_wrapper.camera.samsung.so`, `libhigh_dynamic_range.arcsoft.so` 등)는 **APK 안에 없습니다**. Manifest의 `uses-native-library`로 선언돼 있고, `libnode-jni.so`가 런타임에 디바이스(`/system` 또는 `/vendor`)에서 `dlopen` 합니다. (상세: `native-image-processing.md`)

---

## 1. 전체 Call Graph (Photo 모드, 후면 Auto)

```
[UI] PhotoPresenter.handleShutterButtonClick(InputType)                       classes4  shootingmode/photo/PhotoPresenter.java:2454
  └─ CaptureManagerImpl.requestCapture(InputType, CaptureType)                 classes4  engine/capture/CaptureManagerImpl.java
       └─ (private) requestCapture(InputType, CaptureType, CaptureInfo)        :796
            ├─ createCaptureInfo(...)  → TakePictureType 결정                   :134,:138
            │     isTakingSinglePictureAvailable(captureType, dynamicShotInfo)  :256
            │       dsMode==0 → SINGLE / 아니면 PROCESSING_INSTANT | PROCESSING_POST
            └─ SingleCaptureController.requestSingleCapture(CaptureInfo)        engine/capture/SingleCaptureController.java:895
                 └─ takePicture(CaptureInfo)                                     :488 (호출 :912)
                      ├─ SINGLE            → Engine.addRequest(RequestId.TAKE_PICTURE)
                      └─ PROCESSING_*      → Engine.addRequest(RequestId.TAKE_PROCESSING_PICTURE)
[Request Queue] RequestQueueImpl → Request.execute()                           engine/core/request/
  ├─ TakePictureRequest.execute()                                               TakePictureRequest.java:74
  │     └─ MakerInterface.takePicture(DynamicShotInfo, WatermarkInfoGenerator, captureHint)
  └─ TakeProcessingPictureRequest.execute()                                     TakeProcessingPictureRequest.java:124/134
        ├─ MakerInterface.takeProcessingPicture(...)        (IPP: 즉시 처리)
        └─ MakerInterface.takePostProcessingPicture(...)    (PPP: 백그라운드 후처리)
[core2 Maker] MakerFactory → AutoPhotoMaker / RearPhotoMaker / SelfiePhotoMaker  core2/maker/MakerFactory.java
  AutoPhotoMaker.takePicture(...)                                               core2/maker/AutoPhotoMaker.java:749
    ├─ isSingleProcessingPictureCondition()==true → ProcessingPhotoMakerBase.takeSingleProcessingPicture()  :1107 (YUV, UN_COMP)
    └─ 그 외 → AutoPhotoMaker.takePictureInternal()                              :686 (JPEG, COMP)
  ProcessingPhotoMakerBase.takeProcessingPicture(...)                           ProcessingPhotoMakerBase.java:1067 (멀티프레임)
[Camera2] CamDevice.O(CamDeviceRequestOptions) / CamDevice.N(Sequence, List)     core2/CamDevice.java:246,248 (abstract)
  └─ CamDeviceImpl → CamDeviceRepeatingStatePreview                              core2/device/
        c(CamDeviceRequestOptions) → CameraCaptureSession.capture(...)          :682 / :889
        d(List)                    → CameraCaptureSession.captureBurst(...)     :953 / :1354
        CaptureRequest: createCaptureRequest(template) + Samsung vendor key       CamDeviceImpl.java:1284
          (samsung.android.control.dynamicShotHint / dynamicShotExtraInfo / captureHint 등)
[HAL / ISP]  ── APK 외부 (Samsung camera provider, Unihal) ──
[Buffer] ImageReader.newInstance(...) → BlockingImageReader                     CamDeviceImpl.java:1319
  └─ CamDevicePicTypeImgAvailableCallback.onImageAvailable(ImageReader)          core2/device/CamDevicePicTypeImgAvailableCallback.java:57
       └─ (YUV_420_888 → packed NV21) ImageUtils.convertFlexibleYuv420888ToPackedNV21  core2/util/ImageUtils.java:156 → libimageutils-jni.so
       └─ CamDeviceMultiPicCaptureCallback → CamDevice.MultiPictureCallback
[core2 Maker 콜백] ProcessingPhotoMakerBase.MultiPictureCallback.onPictureTaken(...)  ProcessingPhotoMakerBase.java:280
  ├─ JPEG / JPEG_R            → handleDraftRequest()   (HAL이 이미 인코딩한 결과)
  ├─ RAW_SENSOR/RAW10/RAW12   → handleResourceRequest() (RAW 멀티프레임 입력)
  └─ YUV_420_888/YCBCR_P010   → handleDraftRequest() + handleResourceRequest()
       └─ Sequence.nextRequest(Usage.RESOURCE_IMAGE, ...) → PictureProcessorManager.process(ProcessRequest)  PictureProcessorManager.java:380
            ├─ ProcessType 1/2 → processImmediateProcessor() → ImmediateProcessor → IppNodeController
            └─ ProcessType 3   → PostProcessor.addProcessRequest() → PppNodeController (PostProcessService)
[Node Chain] IppNodeController.configureNodeChain(ProcessRequest)               nodeController/IppNodeController.java
  PREPROCESSOR → [MULTI_FRAME: dsMode별 1개] → SINGLE_UDC → LOCAL_TM → FACE_RESTORATION → BEAUTY
  → UW_DISTORTION → WIDE_DISTORTION → SELFIE_CORRECTION → SINGLE_BOKEH → DUAL_BOKEH → STEREO_PHOTO
  → FILTER_NOT_SUPPORTING_NON_D → NON_DESTRUCTION → FILTER_SUPPORTING_NON_D → WATERMARK → ENCODER
  각 Node: Node.processPicture*() → processPictureYuv/Jpeg/Raw/Heic()           core2/node/Node.java:220-273
  Native Node: NativeNode.nativeCall(Command, args) → libnode-jni.so → dlopen(vendor .so)
[Encoder] ENCODER chain = SecImageCodecNode(v2) + SefNode                         composer/EncoderNodeChainComposer.java:60
  SecImageCodecNodeBase.processPictureYuv() → processPictureInternal() → SecImageCodecNode.encode()
    → nativeCall(SET_CONFIGURATION, CodecConfiguration) / SET_MAIN_IMAGE / (gain map) SET_SUB_IMAGE_FOR_GAIN_MAP
    → libnode-jni.so → libsimba.media.samsung.so (SimbaEncoderNdkV2) / libimagecodec.quram.so / libjpega / libexifa
[결과 콜백] CallbackHelper.PictureCallbackHelper → app PictureCallback
  SingleCaptureController.onPictureTaken(ByteBuffer, PictureDataInfo, CamDevice)   SingleCaptureController.java:775
    └─ handlePictureTaken() → PictureProcessor.process(ByteBuffer, PictureDataInfo, CaptureInfo)  engine/capture/PictureProcessor.java:805
         └─ PictureSavingTask.run() → insertToDb() → DatabaseUtil.insertToDb(...)   PictureProcessor.java:206/212 → util/DatabaseUtil.java:66 (MediaStore)
  (PPP 경로) PostProcessService → postSaving/module/PostSavingModuleImageWrite / ...ScanFile → onPostProcessingPictureTaken(File)
```

---

## 2. 단계별 기록

표 컬럼: 파일 / 클래스 / 메서드 / 호출하는 쪽 / 호출되는 쪽 / native 여부 / 라이브러리 / 이미지 처리와의 관계 / 사실·추정 / 다음 확인 항목

| # | 파일 | 클래스.메서드 | 호출하는 쪽 | 호출되는 쪽 | native | lib | 이미지 관계 | 확실성 | 다음 확인 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | shootingmode/photo/PhotoPresenter.java:2454 | `PhotoPresenter.handleShutterButtonClick` | 셔터 UI | `CaptureManagerImpl.requestCapture` | X | - | 없음(트리거) | 확인됨 | - |
| 2 | engine/capture/CaptureManagerImpl.java:138,256 | `getTakePictureType` / `isTakingSinglePictureAvailable` | requestCapture | - | X | - | **HAL JPEG 직출력(SINGLE)인지 앱 처리(PROCESSING)인지 결정** | 확인됨 | 워터마크/해상도 분기 |
| 3 | engine/capture/SingleCaptureController.java:488 | `SingleCaptureController.takePicture` | requestSingleCapture(:895) | `Engine.addRequest(TAKE_PICTURE / TAKE_PROCESSING_PICTURE)` | X | - | 없음 | 확인됨 | - |
| 4 | engine/core/request/TakePictureRequest.java:74 | `TakePictureRequest.execute` | RequestQueueImpl | `MakerInterface.takePicture` / `takeRawPicture` | X | - | 없음 | 확인됨 | - |
| 5 | engine/core/request/TakeProcessingPictureRequest.java:124,134 | `execute` | RequestQueueImpl | `takeProcessingPicture` / `takePostProcessingPicture` | X | - | IPP/PPP 선택 | 확인됨 | - |
| 6 | core2/maker/AutoPhotoMaker.java:749 | `AutoPhotoMaker.takePicture` | MakerInterface | `takePictureInternal`(COMP=JPEG) / `takeSingleProcessingPicture`(UN_COMP=YUV) | X | - | **YUV를 앱으로 받을지 결정** | 확인됨 | RearPhotoMaker 오버라이드 |
| 7 | core2/maker/ProcessingPhotoMakerBase.java:743-751 | `isExtraPostProcessCondition` / `isSingleProcessingPictureCondition` | AutoPhotoMaker | - | X | - | HEIF(`0x48454946`) 저장이거나 SUPER_HDR이면 단일 촬영도 YUV→앱 인코딩 | 확인됨 | - |
| 8 | core2/maker/AutoPhotoMaker.java:326-343 | `getDsExtraInfo` | 프리뷰 결과 처리 | HAL로 dsExtraInfo 전달 | X | - | FILTER 비트(0x100000) 세팅, **NEED_LTM 비트 해제** | 확인됨 | HAL이 LTM을 대신하는지 |
| 9 | core2/device/CamDeviceImpl.java:1284,1319 | `createCaptureRequest`, `ImageReader.newInstance` | CamDevice.O/N | Camera2 | X | - | 스트림/요청 구성 | 확인됨 | 템플릿 값 |
| 10 | core2/device/CamDeviceRepeatingStatePreview.java:889,1354 | `c(...)`, `d(List)` (난독화) | CamDeviceImpl | `capture` / `captureBurst` | X | - | 멀티프레임 burst 요청 | 확인됨 | - |
| 11 | core2/device/CamDevicePicTypeImgAvailableCallback.java:57 | `onImageAvailable` | ImageReader | MultiPictureCallback | 간접 | libimageutils-jni | YUV888→packed NV21 복사 | 확인됨(변환 함수 존재) / 모든 경로에서 NV21인지는 추정 | stride/P010 |
| 12 | core2/maker/ProcessingPhotoMakerBase.java:280 | `MultiPictureCallback.onPictureTaken` | CamDevice | handleDraftRequest / handleResourceRequest | X | - | 포맷별 분기 | 확인됨 | - |
| 13 | core2/processor/PictureProcessorManager.java:380 | `process(ProcessRequest, Context)` | Maker | ImmediateProcessor / PostProcessor | X | - | IPP/PPP 디스패치 | 확인됨 | - |
| 14 | core2/processor/nodeController/IppNodeController.java | `configureNodeChain` / `createNodeChain` | ImmediateProcessor | 각 NodeChainComposer | X | - | **체인 순서 정의** | 확인됨 | - |
| 15 | core2/processor/container/NodeChainConfiguration.java:195-290 | `create*NodeChainConfiguration` | ProcessRequestImpl:171 | - | X | - | **어떤 체인이 켜지는지 결정** | 확인됨 | - |
| 16 | core2/node/imageCodec/samsung/v2/SecImageCodecNode.java:135 | `encode` | ENCODER chain | `nativeCall(...)` | O | libnode-jni → libsimba / libimagecodec.quram | **최종 JPEG/HEIF 인코딩** | 확인됨 | CodecConfiguration.quality |
| 17 | engine/capture/PictureProcessor.java:805, 206 | `process` → `PictureSavingTask.insertToDb` | SingleCaptureController.handlePictureTaken | DatabaseUtil.insertToDb | X | - | 저장만(픽셀 변경 없음) | 확인됨 | - |

---

## 3. 경로 분기 — 가장 중요한 사실

### 3.1 dsMode는 HAL이 결정합니다
- 프리뷰 `CaptureResult`의 벤더 태그 `samsung.android.control.dynamicShotHint`(`SemCaptureResult.f6728Q`)와 `samsung.android.control.dynamicShotExtraInfo`(`f6727P`)가 `MakerCallbackManager`(:15080~15142)에서 `DynamicShotInfo`로 바뀌어 앱에 전달됩니다.
- `DynamicShotUtils.getDsProcessingMode(hint, extraInfo)` (`core2/util/DynamicShotUtils.java:108`)
- 즉 **장면이 SINGLE / MF_HDR / LL_HDR / SUPER_NIGHT / AI_ISP 중 무엇인지는 HAL(ISP 3A·장면 판단)이 정합니다**. 앱은 그 결과에 맞춰 노드 체인을 고릅니다. **(확인됨)**

### 3.2 세 가지 실제 경로

| 경로 | 조건 | 앱이 받는 포맷 | 앱 노드 처리 | 최종 인코딩 |
|---|---|---|---|---|
| **A. SINGLE (HAL JPEG)** | dsMode==0, 워터마크 없음, JPEG 저장, 필터 없음 | JPEG / JPEG_R(Ultra HDR) | **없음** (콜백으로 바로 저장) | HAL |
| **B. Single Processing (IPP)** | dsMode==0이지만 HEIF 저장 / SUPER_HDR / EXTRA_POST_PROCESS 등 | YUV_420_888 또는 P010 | Single-frame 체인 + ENCODER | 앱(`SecImageCodecNode`) |
| **C. Multi-frame Processing (IPP/PPP)** | dsMode≠0 (HDR, Night, AI ISP, SR …) | RAW 또는 YUV burst | MULTI_FRAME 노드 + Single-frame 체인 + ENCODER | 앱(`SecImageCodecNode`) |

**의미**: 경로 A(밝은 환경의 일반 사진, 아주 흔함)에서는 **앱 Java/Kotlin 코드가 픽셀을 전혀 만지지 않습니다**. 이 경우 look은 100% HAL/ISP 튜닝입니다. 앱에서 look을 바꾸려면 경로 B로 강제해야 합니다(`isExtraPostProcessCondition` 등). **(확인됨, 런타임 비율은 확인 필요)**

### 3.3 dsMode → Multi-frame 체인 (NodeChainKeyContainer.java:210-375, 확인됨)

| dsMode | 이름 예 | 체인 | NodeFeatureGroup |
|---|---|---|---|
| 0, 46 | SINGLE | SINGLE (멀티프레임 없음) | - |
| 10-15 | HIFI_PICK, HIFI_MERGE_* | HIFI_LLS | HIFILLS (`MpiHifiLlsNode`) |
| 20-29 | MFHDR_MERGE_* | MF_HDR | MFHDR (`ArcMfHdrNode` v1/v2/v4) |
| 30-39, 60 | LLHDR_MERGE_* | LL_HDR | LLHDR (`ArcLlHdrNode` v1/v4) |
| 70,71,75 | MF_SR_MERGE* | SR | SUPER_RESOLUTION (`ArcSRNode`) |
| 80-88, 260-269, 320-321 | SUPER_NIGHT_* | SUPER_NIGHT | SUPER_NIGHT (`ArcSuperNightNode` v3 / `MpiSuperNightNode` v2) |
| 90,91 | HIGHRES_* | HIGH_RES | HIGH_RES (`ArcHighResNode`) |
| 120-122 | SIE_MERGE* | SIE | IMAGE_ENHANCE (`ArcSIENode`) |
| 150 | SS_HDR_MERGE | SS_HDR | SSHDR |
| 180-186, 310-312 | AI_HIGHRES_* | AI_HIGH_RES | AIMODE (`SecAiHighResNode` v1~v3) |
| 191-198, 240-243 | HYBRID_*HDR_* | HYBRID_HDR | HYBRIDHDR (`ArcHybridHdrNode`) |
| 200, 340 | AEB_HDR_MERGE | AEB_HDR | AEBHDR |
| 270-303 | AI_ZOOM_* | AI_CLEAR_ZOOM | AI_CLEAR_ZOOM |
| 290-294 | TETRA_SR_* | TETRA_SR | SUPER_RESOLUTION_TETRA |
| 334-342 | AI_ISP_*_V2 | AI_ISP | AI_ISP (`SecAiIspNode`) |
| 350, 351 | DE_FLICKER* | DE_FLICKER(_HDR) | DE_FLICKER(_HDR) |
| 360, 361 | HEXADECA_SR_* | HEXADECA_SR | HEXADECA_SR |

노드 구현체(벤더·버전)는 디바이스 floating feature `SEC_FLOATING_FEATURE_CAMERA_CONFIG_VENDOR_LIB_INFO`로 고릅니다 (`core2/node/NodeFeatureLoader.java:29`). **기기마다 다르므로 대상 기기에서 확인이 필요합니다.**

---

## 중간 결과

### Confirmed
- 셔터 → `CaptureManagerImpl` → `SingleCaptureController` → `TakePictureRequest/TakeProcessingPictureRequest` → `AutoPhotoMaker` → `CamDevice` → Camera2 `capture/captureBurst` 경로.
- 결과 이미지는 `ProcessingPhotoMakerBase.MultiPictureCallback.onPictureTaken`에서 포맷별로 나뉘고, `PictureProcessorManager.process` → `IppNodeController/PppNodeController`의 노드 체인으로 들어갑니다.
- 노드 체인 순서는 `IppNodeController`/`PppNodeController`에 하드코딩돼 있고, 마지막은 항상 `WATERMARK → ENCODER(SecImageCodecNode)`입니다.
- dsMode(HDR/Night 등)는 HAL 벤더 태그로 결정됩니다.
- dsMode==0이고 JPEG 저장이면 HAL JPEG를 그대로 저장합니다(앱 픽셀 처리 없음).

### Likely
- 일반 주간 촬영의 상당수는 경로 A(SINGLE, HAL JPEG)일 가능성이 큽니다(확인 필요: logcat의 `DynamicShotMode` 로그).
- YUV 버퍼는 packed NV21 + stride 패딩 형태로 노드에 전달될 가능성이 큽니다(`SecFilterNode`가 `rowStride`, `heightSlice`를 넘김).

### Unknown
- HAL 내부 ISP 파이프라인(demosaic, CCM, NR, sharpening, GTM)의 구체 파라미터 — APK 밖.
- 대상 기기의 `SEC_FLOATING_FEATURE_CAMERA_CONFIG_VENDOR_LIB_INFO` 값(어느 벤더/버전 노드가 실제로 로드되는지).
- 경로 A/B/C 실사용 비율.

### Next Targets
- 실기기 logcat: `NodeChainConfiguration`, `IppNodeController - configureNodeChain`, `createMultiFrameNodeChainConfiguration - dsMode` 로그로 실제 체인 확인.
- `RearPhotoMaker.isExtraPostProcessCondition` → `UnihalMetadataUtils.b(...)` 조건.
- `ImageUtils.convertFlexibleYuv420888ToPackedNV21` 호출 위치(`MakerUtils.java:122`)와 P010 경로.
