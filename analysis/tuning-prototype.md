# Tuning Prototype — 최종 YUV rendering 수정 가능성 검증 (2단계)

대상: Samsung Camera `com.sec.android.app.camera` **16.5.00.55** (1단계와 같은 jadx 출력)
범위: `EncoderNodeChainComposer`, `DynamicFunctionNode`, `SecAiIspNode.setGlobalToneMap()`, `SecImageCodecNode`와, 이 다섯 지점이 직접 호출하는 클래스·JNI 심볼만 읽었습니다. APK는 수정하거나 빌드하지 않았습니다.
경로 표기: `core2/` = `sources/com/samsung/android/camera/core2/`

표기:
- **[확인됨]** 코드에서 직접 확인함
- **[추정]** 코드 정황상 높은 확률이지만 직접 확인하지 못함
- **[확인 필요]** APK 밖에 있거나 정적 분석으로 확인할 수 없음

---

## 0. 가장 중요한 정정

질문에서 전제한 흐름은 **실제 코드와 다릅니다.**

```
(전제)  최종 YUV → DynamicFunctionNode → SecImageCodecNode → JPEG/HEIF
(실제)  최종 YUV → [post 체인들] → ENCODER 체인 { SecImageCodecNode → SefNode } → JPEG/HEIF
```

- `EncoderNodeChainComposer.create()`가 만드는 체인에는 **ImageCodecNode와 SefNode 두 개만** 있습니다. DynamicFunctionNode는 없습니다. **[확인됨]** (`core2/processor/nodeController/composer/EncoderNodeChainComposer.java:60-68`)
- 앱 전체에서 DynamicFunctionNode를 생성하는 곳은 `OriginalDraftNodeChainComposer.create()` 한 곳뿐입니다. **[확인됨]** (`.../composer/OriginalDraftNodeChainComposer.java:54`)
- 그 체인은 PPP의 **draft JPEG 경로**(`ORIGINAL_DRAFT → FILTER → WATERMARK → DRAFT_ENCODER`)에 속합니다. 하는 일은 복구용 원본 사본 저장(`setOriginalDraftForRecovery`)뿐이고 pixel은 바꾸지 않습니다. **[확인됨]** (`PppNodeController.java:51-58`, `OriginalDraftNodeChainComposer.java:33-42`)

그래서 결론은 이렇습니다. **기존 DynamicFunctionNode를 그대로 쓰는 것은 의미가 없습니다.** 대신 DynamicFunctionNode는 Java callback 하나만 받는 범용 노드이므로, **새 인스턴스를 ENCODER 체인 맨 앞에 끼워 넣으면** 원하는 위치(최종 YUV → codec 직전)를 정확히 얻을 수 있습니다. 아래 분석과 prototype은 이 전제로 작성했습니다.

---

## 1. 실제 데이터 흐름

### 1-1. 체인 연결 구조 [확인됨]

```
CamDevice.N(sequence, UN_COMP)   ← HEIF, 또는 dsMode≠0 처리 경로
   │ YUV ImageBuffer
   ▼
[MF 체인] AI_ISP / SUPER_NIGHT / MF_HDR / ... (dsMode에 따라 1개)    ← Samsung HDR/Night/AI ISP
   ▼
[post 체인] SINGLE_UDC → LOCAL_TM → FACE_RESTORATION → BEAUTY → UW/WIDE_DISTORTION
            → SELFIE_CORRECTION → SINGLE/DUAL_BOKEH → STEREO → FILTER… → NON_DESTRUCTION
            → WATERMARK                                          (IppNodeController.java 생성자)
   ▼  mLastPostProcessingNodeChain.c(ENCODER)                   (NodeControllerBase.java:133)
[ENCODER 체인]  SecImageCodecNode → SefNode                      (EncoderNodeChainComposer.java:62-66)
   ▼
JPEG / HEIC / JPEG_R / HEIC_ULTRAHDR DirectBuffer
```

- 체인 실행은 `NodeChain.p()`(`core2/node/NodeChain.java:347`)입니다. 체인이 비활성(`e(false)`)이면 입력을 그대로 넘기고, 활성이면 첫 InputPort부터 실행한 뒤 다음 체인으로 넘어갑니다. 모두 **동기 호출**입니다.
- 체인 안에서 노드 사이 연결은 `NodeChain.b()`(`:194`)가 앞 노드의 OutputPort를 다음 노드의 InputPort에 `Node.connectPort`로 묶는 방식입니다.
- 각 노드의 InputPort(`core2/node/InputPort.java:34`)는 `needProcessPicture()`(= 노드가 ACTIVATED 상태인지)가 true일 때만 처리하고, 아니면 **입력을 그대로 다음 노드로 넘깁니다.** 그래서 노드를 추가해도 activate하지 않으면 동작이 바뀌지 않습니다. 이것이 안전 스위치가 됩니다.

### 1-2. 노드가 받는 buffer [확인됨 / 일부 추정]

| 항목 | 값 | 근거 |
|---|---|---|
| 컨테이너 | `ImageBuffer extends DirectBuffer`. native heap의 direct `ByteBuffer`라 **Java에서 읽기·쓰기 가능** | `core2/util/ImageBuffer.java:14,127-130` |
| 포맷 dispatch | `ImageInfo.getFormat()`이 `YUV_420_888`(35) 또는 `YCBCR_P010`(54)이면 `processPictureYuv()` 호출 | `core2/node/Node.java:205-255`, `:965-968` |
| 8/10-bit | `YUV_420_888` = 8-bit. `YCBCR_P010` = 샘플당 16-bit 컨테이너(상위 10-bit 유효) | `SemImageFormat.java:117,134`. P010 비트 배치는 Android 표준 정의 **[추정]** |
| plane 구조 | 연속된 semi-planar. `Y[rowStride × heightSlice]` 뒤에 interleaved chroma `[rowStride × height/2]` | 버퍼 크기 식 `ImageUtils.getNV21BufferSize / getYCbCrP010BufferSize` (`ImageUtils.java:448-460`) |
| chroma 순서 | 8-bit는 **NV21 (V,U 순)** | `convertFlexibleYuv420888ToPackedNV21`, `getNV21BufferSize` 명명, `StrideInfo(Image)`가 plane[2](V) 오프셋으로 heightSlice를 계산함 **[추정]**. native 복사 `nativePutByteBufferFromImage` 내부는 **[확인 필요]** |
| width/height | `imageInfo.getSize()` | `ImageInfo.java:95` |
| stride | `imageInfo.getStrideInfo()` → `rowStride`, `heightSlice`, `isPacked` (packed이면 rowStride==width, heightSlice==height) | `StrideInfo.java:10-80` |
| 실제 값 | 기기·해상도별 rowStride, heightSlice, format | **[확인 필요]**. 기존 로그 `"processPicture - Start : " + imageBuffer`(`PictureProcessCore.java:38`)에 format과 stride가 이미 찍히므로 logcat으로 확인 가능 |

### 1-3. Ownership과 in-place 여부 [확인됨]

- `DynamicFunctionNode.processPictureYuv()`는 callback을 호출한 뒤 **같은 `imageBuffer` 객체를 그대로 반환**합니다(`DynamicFunctionNode.java:44-47`). 즉 **input buffer와 output buffer가 같습니다(in-place).**
- callback 안에서 buffer 내용을 바꾸면 다음 노드(SecImageCodecNode)가 바뀐 내용을 그대로 읽습니다.
- `PictureProcessCore.h()`가 처리 전에 `imageBuffer.rewind()`를 부르므로(`PictureProcessCore.java:88`) position은 0에서 시작합니다. callback이 끝날 때도 position을 0으로 돌려 놓아야 합니다.
- buffer 해제(release)는 체인 바깥 프로세서가 담당하므로, 노드 안에서 release하면 안 됩니다. **[추정]** (1단계 분석과 일치)

---

## 2. DynamicFunctionNode 정밀 분석 [확인됨]

파일: `core2/node/DynamicFunctionNode.java` (53줄 전체)

| 항목 | 내용 |
|---|---|
| 상속 | `extends Node` (Java 노드, `NativeNode` 아님) |
| 생성 | `new DynamicFunctionNode(FunctionConsumer)` 또는 `(NodeTagComposer, FunctionConsumer)`. NodeId = `NODE_DYNAMIC_FUNCTION(370)` |
| callback | `interface FunctionConsumer { void a(ExtraBundle, ImageBuffer); }` (r8로 메서드명이 `a`가 됨) |
| 처리 함수 | `processPictureYuv/Jpeg/Raw/Heic` 모두 `functionConsumer.a(extraBundle, imageBuffer); return imageBuffer;` |
| configure | 자체 configure 없음. 체인 composer가 `initialize(true)`(또는 `NodeChain.i(true)`)를 불러 activate |
| JNI/native | **없음.** 순수 Java |
| pixel 접근 | 가능. `imageBuffer.rentByteBuffer()` / `returnByteBuffer()` 또는 DirectBuffer의 get/put |
| 다음 노드 전달 | 반환한 ImageBuffer를 OutputPort가 다음 InputPort로 넘김 (같은 객체) |
| 메타데이터 | `@RequiredCaptureMetadata(CONTROL_DYNAMIC_SHOT_HINT, CONTROL_DYNAMIC_SHOT_EXTRA_INFO)` → `imageInfo.getCaptureMetadata()`로 dsHint 등을 읽을 수 있음 |

**"DynamicFunctionNode 내부에 간단한 YUV 색상/톤 처리를 추가할 수 있는가?" → 예. 다만 DynamicFunctionNode 클래스 자체는 수정할 필요가 없습니다.**
처리 로직은 `FunctionConsumer` 구현체(새 클래스)에 넣고, 그 노드를 `EncoderNodeChainComposer.create()`에서 체인 맨 앞에 추가하면 됩니다. 기존 OriginalDraft 경로의 DynamicFunctionNode(`L3/k.java:104-106`)는 건드리지 않습니다.

주의할 점:
- `PictureProcessCore`는 노드 처리 시간이 **500ms를 넘으면 경고**를 남깁니다(`PictureProcessCore.java:21,72-74`). 강제 종료는 아니지만, 50MP/200MP 버퍼를 Java에서 byte 단위로 돌리면 이 기준을 넘을 수 있습니다. → prototype은 행(row) 단위 bulk get/put과 256-entry LUT를 쓰고, 처리 시간을 로그로 남깁니다.

---

## 3. `SecAiIspNode.setGlobalToneMap()` 분석

파일: `core2/node/aiIsp/samsung/v1/SecAiIspNode.java:326-334`

```java
private void setGlobalToneMap() {
    double[] dArr = (double[]) SemCaptureResult.b(this.mRepresentingCaptureMetadata, SemCaptureResult.f6743W);
    if (dArr == null) { CLog.w(..., "setGlobalToneMap: failed because globalToneMap is null"); }
    else { CLog.i(..., "setGlobalToneMap: data size = %d", dArr.length);
           nativeCall(NATIVE_COMMAND_SET_GLOBAL_TONE_MAP, dArr); }
}
```

| 항목 | 결과 |
|---|---|
| signature | `private void setGlobalToneMap()`. **파라미터 0개** [확인됨] |
| 데이터 출처 | `SemCaptureResult.f6743W` = CaptureResult vendor key **`samsung.android.control.globalToneMap`**, 타입 **`double[]`** (`SemCaptureResult.java:518`) [확인됨] |
| 누가 만드나 | **HAL이 만들어 CaptureResult로 돌려줌.** 앱은 읽기만 함. `SemCaptureRequest`에는 같은 이름의 request key가 없음(검색 결과 0건) [확인됨] |
| 어느 프레임 값인가 | 멀티프레임 중 **첫 프레임의 metadata**(`mRepresentingCaptureMetadata`, `initProcessPicture()`의 `isFirstProcessPicture()` 블록, `:396-412`) [확인됨] |
| 호출 시점 | `initProcessPicture` → setInitCapture → setCaptureInfo → setShotMode → setCropInfo → **setGlobalToneMap** → … → 이후 `setInputData` / `makeAiIsp` [확인됨] |
| native 전달 | Command id `Message.MF_PREPARE_END` = **112**, 인자 `double[]` → `libnode-jni.so`의 `aiIsp::samsung::v2::SecAiIspNode::setGtmCurveInfo(Array<double>*)` (`0xda898`) [확인됨] |
| native 내부 | 디스어셈블 결과 Array의 `size`(+0x8)와 `data`(+0x10)를 null 검사만 하고 **고정 길이 검사 없이** vendor wrapper(`BayerAIPhotoWrapperIF`, APK 밖 `dlopen`)로 넘김 [확인됨: 길이 비교 명령 없음] |
| 같은 key를 쓰는 다른 노드 | MpiSuperNightNode(cmd 118), SecTetraSrNode, SecHexadecaSrNode, ArcMacroRawSrNode, ArcAiClearZoomNode, SecAiHighResNode(cmd 115) [확인됨] |
| curve point 개수 | **[확인 필요]** Java/JNI 어디에도 상수가 없음 |
| 값 범위, x/y 배치(interleaved인지 y만 있는지) | **[확인 필요]** |
| integer/float | 저장 형식은 `double` [확인됨]. 의미상 정규화(0–1) 값인지 code value(0–1023 등)인지는 **[확인 필요]** |
| EV/gain 관련 | GTM 배열 자체에는 없음. EV는 별도 key(`SENSOR_CAPTURE_EV`, `SENSOR_MULTI_FRAME_EV`, `SENSOR_DRC_RATIO`)로 `ExtraCaptureInfo`를 통해 전달 [확인됨: SecAiIspNodeBase의 RequiredCaptureMetadata] |

**Input tone → GTM curve → Output tone 관계:** AI ISP는 `processPictureRaw()`로 **RAW(Bayer)** 를 받아 YUV를 만드는 노드입니다(`SecAiIspNode.java:427-430`, native `BayerAIPhotoWrapperIF`). 그래서 GTM curve는 "vendor 알고리즘이 RAW→YUV rendering을 할 때, HAL이 같은 장면에 적용한 global tone curve를 참고값으로 쓰는 것"으로 해석하는 것이 가장 자연스럽습니다. 하지만 vendor가 이 curve를 **그대로 적용하는지, 참고만 하는지, 무시하는지는 [확인 필요]** 입니다.

**"Java에서 GlobalToneMap curve를 바꾸면 최종 사진의 tone response가 바뀌는가?"**
→ **확인할 수 없습니다.** 기술적으로 `nativeCall` 직전의 `double[]`은 Java에서 바꿀 수 있지만, (1) AI ISP 경로(RAW 입력 dsMode)에서만 효과가 있고, (2) 배열 구조를 모르는 채로 바꾸면 vendor 라이브러리가 잘못 동작하거나 crash할 수 있습니다.

확인용 로그 (읽기 전용, 값은 바꾸지 않음):
1. `SecAiIspNode.setGlobalToneMap()`의 `nativeCall` 직전에 `Arrays.toString(dArr)`(또는 앞뒤 8개와 min/max/단조성)을 `CLog.i`로 출력 → 길이, 범위, x/y interleave 여부를 파악
2. 같은 촬영에서 `dsHint`, `SENSOR_CAPTURE_EV`, `SENSOR_DRC_RATIO`, 장면(역광/야간)을 함께 출력 → curve가 장면에 따라 바뀌는지 확인
3. 같은 key를 MpiSuperNightNode에서도 출력해 비교 (같은 HAL 출력인지)
4. 그다음 단계에서야 "배열 전체 × 1.0(무변경)" → "중간값만 아주 조금 변경"으로 A/B

---

## 4. `SecImageCodecNode` 분석

파일: `core2/node/imageCodec/samsung/v2/SecImageCodecNode.java`, `.../samsung/SecImageCodecNodeBase.java`

| 항목 | 결과 |
|---|---|
| 입력 포맷 (encode) | `isEncodeSupported()`: **YUV_420_888, FLEX_RGBA_8888, YCBCR_P010**만 encode (`SecImageCodecNodeBase.java:221-224`, 매핑 `:124-150`) [확인됨] |
| 출력 포맷 | `CodecConfiguration.outputFormat` = `ExtraBundle.e`(없으면 256=JPEG). `EncoderNodeChainComposer.configure()`에서 설정 (`EncoderNodeChainComposer.java:45`). 값: JPEG 256, JPEG_R 4101, HEIC 1212500294, HEIC_ULTRAHDR 4102 (`SemImageFormat.java:83,151,168`) [확인됨] |
| encode 순서 | `getInputImageBuffer`(stride 설정, 필요하면 valid region crop) → `SET_CONFIGURATION` → `SET_MAIN_IMAGE(BufferInfo, JpegMetadata, byte[])` → (Ultra HDR이면) gain map sub image → `START_ENCODING` → `DirectBuffer` (`SecImageCodecNode.java:135-235`) [확인됨] |
| tone mapping 완료 여부 | codec은 받은 YUV를 압축만 함. Java 쪽에 tone 처리 없음 → **입력 시점에 SDR rendering은 이미 끝난 상태** [확인됨: Java] / native encoder 내부 처리 [확인 필요] |
| 색공간 | `JpegMetadata`에 `setAvailableColorSpaceModes()`, `setIccProfile()`가 들어감 (`SecImageCodecNodeBase.java:242`). 실제 sRGB인지 Display-P3인지는 **[확인 필요]** |
| bit depth | 8-bit(YUV_420_888) 또는 10-bit(P010). 어떤 설정에서 P010이 오는지는 **[확인 필요]** (logcat의 format으로 확인) |
| gain map / Ultra HDR | 출력이 JPEG_R 또는 HEIC_ULTRAHDR이면 `ExtraBundle "multiPicture.extraYuvImageForGainMap"`(다른 EV의 YUV)과 EV 값을 native로 넘겨 gain map을 만듦 (`SecImageCodecNode.java:126-133,192-206`) [확인됨]. 계산 방식(main과 sub를 비교하는지)은 **[추정]** |
| EXIF | `getJpegMetadata()` → native에서 기록 (`SecImageCodecNodeBase.java:361`) [확인됨] |
| XMP / SEF | 파노라마 XMP는 codec에서 처리(`:220-224`). 나머지 Samsung 확장(SEF)은 다음 노드 `SefNode`에서 처리 [확인됨] |
| 원본 보관 | `putExtraImageBuffer()`: non-destructive 편집용 원본을 `ExtraBundle.f6001f`에 저장 (`SecImageCodecNodeBase.java:287-347`). **codec 안에서 실행되므로 앞 노드에서 수정한 결과가 "원본"으로 저장됨** [확인됨] |

**codec 직전이 최종 이미지 tuning에 가장 적합한 위치인가?**
→ **예. 단, 조건이 붙습니다.**
- 장점: Samsung HDR/Night/AI ISP, LocalTM, Beauty, Bokeh, Filter, Watermark가 모두 끝난 **최종 SDR YUV**이고, 바로 다음이 압축입니다. 출력 포맷(HEIC/JPEG)과 관계없이 같은 위치이며, IPP·PPP·SPP 세 controller가 모두 같은 ENCODER 체인을 configure합니다(`IppNodeController.java:36`, `PppNodeController.java:308`, `SppNodeController.java:33`).
- 조건 1: **Ultra HDR(JPEG_R / HEIC_ULTRAHDR)에서는 쓰면 안 됩니다.** main 이미지만 바꾸면 gain map과 어긋나 HDR 화면에서 결과가 틀어질 수 있습니다. → prototype은 출력이 일반 HEIC일 때만 동작하게 합니다.
- 조건 2: Watermark 체인 뒤이므로 watermark 픽셀도 함께 바뀝니다 (prototype 단계에서는 무시해도 됨).
- 조건 3: PPP는 먼저 **draft JPEG**를 따로 만들어 둡니다(DRAFT_ENCODER 체인). 이 draft에는 tuning이 적용되지 않으므로, 갤러리에서 잠깐 보이는 미리보기와 최종본이 다를 수 있습니다.

---

## 5. JPEG vs HEIF 경로 검증

### 5-1. 일반 JPEG(dsMode 0)는 HAL JPEG가 바로 저장된다 [확인됨]

```
CaptureManagerImpl.getTakePictureType()                         CaptureManagerImpl.java:137-139
  └ isTakingSinglePictureAvailable(): dynamicShotInfo.f5965a(dsMode)==0 → true     :256-258
      → TakePictureType.SINGLE → TakePictureRequest → MakerInterface.takePicture()
AutoPhotoMaker.takePicture()                                    core2/maker/AutoPhotoMaker.java:749-763
  ├ isSingleProcessingPictureCondition(...) == false (JPEG 설정이고 SuperHDR 아님)
  └ takePictureInternal()                                       :686
       PicFormat.COMP(JPEG 스트림) 요청                          :688
       return this.mCamDevice.O(builder.a())                    :715   ← Sequence/ProcessRequest 없음
  → HAL이 JPEG 압축까지 끝낸 buffer → CamDevicePicTypeImgAvailableCallback → 앱 저장 (1단계 capture-flow.md)
```
노드 체인(`ProcessRequest`)이 만들어지지 않으므로 ENCODER 체인도 실행되지 않습니다. 이 경로에서는 **앱이 pixel에 손댈 수 없습니다.**

참고: JPEG라도 **dsMode≠0**(HDR/Night 등 PROCESSING_INSTANT/POST)이면 처리 체인을 거쳐 ENCODER(outputFormat=256)로 갑니다. 그래서 ENCODER에 넣은 노드는 그런 JPEG에도 적용됩니다. prototype은 HEIC만 처리하도록 제한합니다.

### 5-2. HEIF는 YUV로 앱 처리 체인에 들어온다 [확인됨]

```
AutoPhotoMaker.takePicture()                                    AutoPhotoMaker.java:759-760
  └ ProcessingPhotoMakerBase.isSingleProcessingPictureCondition()   ProcessingPhotoMakerBase.java:747-752
        DynamicShotUtils.getDsExtraInfoNeedSuperHdr(dsExtraInfo) → true
        || isExtraPostProcessCondition(): 1212500294 == this.mPictureEncodeFormat   :743-745
           (1212500294 = 0x48454943 = 'HEIC' = ImageFormat.HEIC)
  └ takeSingleProcessingPicture()                               :1102
       new ProcessRequestImpl.Sequence(this.mPictureEncodeFormat, size, ProcessType.SINGLE_PROCESS, ...)
       PicFormat.UN_COMP(YUV 스트림) 요청                         :1140-1141
       this.mCamDevice.N(sequence, ...)                          :1145
  → YUV → ProcessRequest → (post 체인) → ENCODER(SecImageCodecNode, outputFormat=HEIC)
```
- 조건: 사진 저장 포맷이 HEIF(`mPictureEncodeFormat == HEIC`, `MakerBase.java:1336`에서 DeviceConfiguration으로 설정)이거나, dsExtraInfo에 SuperHDR 비트가 있으면 YUV 경로로 갑니다.
- `ExtraBundle.e`(codec 출력 포맷)는 `ProcessRequestImpl` 생성 시 Sequence의 encode format으로 채워집니다(`ProcessRequestImpl.java:159`) **[추정: 인자 i7 = Sequence encode format]**.

---

## 6. 최소 prototype 설계

### 6-1. 테스트 선택: **Test A — 전체 밝기 변화 (Y plane만, 256-entry LUT)**

이유:
- **검증이 가장 쉽습니다.** 같은 frame의 A(입력)와 B(출력) dump를 비교하면 `B.Y[i] == LUT[A.Y[i]]`, `B.UV == A.UV`(byte 단위 완전 일치)로 **정답을 수식으로 확인**할 수 있습니다.
- chroma를 건드리지 않으므로 색이 틀어질 위험이 없습니다 (saturation/Test C는 NV21 순서가 틀리면 색이 뒤집힘).
- tone curve(Test B)와 highlight roll-off(Test D)도 결국 같은 LUT 구조라서, Test A가 통과하면 LUT 내용만 바꿔 확장할 수 있습니다.
- LUT 예: `out = clamp(round(in × 1.10))`, 단 `0→0` 고정 (눈으로도 바로 보이고 dump로 검증 가능). 처음에는 **8-bit YUV_420_888만** 처리하고 P010은 건너뜁니다.

### 6-2. 노드 삽입 위치

```
ENCODER 체인 (수정 후)
  [DynamicFunctionNode(TuningConsumer)] → SecImageCodecNode → SefNode
        ▲ CUSTOM_TUNING_ENABLED=false면 initialize(false) → InputPort가 그대로 통과
```

### 6-3. 처리 로직 (Java로 표현. 실제 적용은 smali 수정)

```java
// 새 클래스: com.samsung.android.camera.core2.node.TuningConsumer (기존 클래스 수정 없음)
final class TuningConsumer implements DynamicFunctionNode.FunctionConsumer {
    static final boolean CUSTOM_TUNING_ENABLED = false;   // 기본값 false
    static final boolean YUV_DUMP_ENABLED      = false;   // 기본값 false
    static final int HEIC = 1212500294;
    private static final byte[] LUT = buildLut(1.10f);

    @Override public void a(ExtraBundle eb, ImageBuffer buf) {
        if (!CUSTOM_TUNING_ENABLED) return;
        ImageInfo info = buf.getImageInfo();
        Integer out = (Integer) eb.c(ExtraBundle.e);                 // codec 출력 포맷
        if (out == null || out != HEIC) return;                       // HEIC만 (Ultra HDR, JPEG 제외)
        if (info.getFormat() != SemImageFormat.YUV_420_888) return;   // 8-bit만
        Size sz = info.getSize(); StrideInfo st = info.getStrideInfo();
        long t0 = SystemClock.elapsedRealtime();
        if (YUV_DUMP_ENABLED) dump(buf, "A_in", info);
        ByteBuffer bb = buf.rentByteBuffer();
        try {
            byte[] row = new byte[sz.getWidth()];
            for (int y = 0; y < sz.getHeight(); y++) {               // Y plane만
                int off = y * st.getRowStride();
                bb.position(off); bb.get(row);
                for (int x = 0; x < row.length; x++) row[x] = LUT[row[x] & 0xFF];
                bb.position(off); bb.put(row);
            }
        } finally { bb.rewind(); buf.returnByteBuffer(bb); }
        if (YUV_DUMP_ENABLED) dump(buf, "B_out", info);
        CLog.i("TuningConsumer", "applied %s stride=%s %dms", sz, st, SystemClock.elapsedRealtime() - t0);
    }
}
```
- in-place 처리이고, row buffer 하나 외에는 메모리를 더 쓰지 않습니다.
- chroma 영역(`rowStride × heightSlice` 이후)은 건드리지 않습니다.

---

## 7. A/B 비교용 dump

### 7-1. 기존 dump 기능 (참고) [확인됨]
`PictureProcessCore.d()/e()`가 노드마다 `input_<노드명>`과 `processed_<노드명>`을 `DumpUtils.dumpCaptureIfEnabled()`로 저장합니다(`PictureProcessCore.java:50-64`). 하지만 조건이 `Node.DEBUG`(= `!isShipMode()`, 개발 빌드)이면서 `sec.camera.CAPTURE_DUMP=T` system property여야 하므로(`DumpUtils.java:118-121`, `DebugUtils.java:49`) **양산 기기에서는 켤 수 없습니다** [추정: 양산 기기에서 property 설정 불가].

### 7-2. prototype 전용 dump
- 위치: `TuningConsumer.a()` 안, LUT 적용 **직전(A)과 직후(B)**. 같은 호출 안이므로 **같은 frame**이 보장됩니다.
- 파일명: `<A_in|B_out>_<width>x<height>_s<rowStride>_h<heightSlice>_<imageInfo.getTimestamp()>.nv21` (timestamp가 같으면 한 쌍)
- 쓰기 방식: `DumpUtils.dumpToFile(ByteBuffer, name)`과 같은 FileChannel 방식(`DumpUtils.java:209-236`). 디렉터리는 앱 전용 경로.
  - 기존 `DUMP_DIRECTORY = /data/user/0/com.sec.android.app.camera/files/.dump`는 non-debuggable 앱이라 `adb`로 꺼내기 어렵습니다 **[확인 필요]**.
  - `/sdcard/Android/data/com.sec.android.app.camera/files/tuning_dump/`가 후보입니다. adb pull 가능 여부는 **[확인 필요]**.
- 기본값 `YUV_DUMP_ENABLED = false`. 12MP 기준 dump 1장이 약 18MB이므로 한 번에 몇 장만 찍습니다.
- 검증: PC에서 `A.Y`에 LUT를 적용한 값과 `B.Y`가 완전히 같은지, `A.UV`와 `B.UV`가 같은지 비교합니다. 그다음 최종 HEIC를 decode해 tuning ON/OFF 결과와 비교합니다.

---

## 8. 실제 수정 후보

| 목적 | 파일 (jadx 기준 / smali) | 클래스 | 메서드 | 수정 내용 | 위험도 |
|---|---|---|---|---|---|
| DynamicNode 삽입 | `core2/processor/nodeController/composer/EncoderNodeChainComposer.java` (classes3.dex smali) | `EncoderNodeChainComposer` | `create(CamCapability)` (`:60-68`) | `nodeChain.b(new DynamicFunctionNode(new TuningConsumer()), DynamicFunctionNode.class, null, PORT_TYPE_PICTURE)`를 **ImageCodec 추가보다 먼저** 호출 | 중 (체인 구조 변경. 체인 key 매핑 `NodeChainConfiguration.java:211`은 그대로 둠) |
| DynamicNode 활성화 | 같은 파일 | `EncoderNodeChainComposer` | `configure(...)` (`:32-57`) | `nodeClassList` 검사 블록 안에서 `((DynamicFunctionNode) nodeChain.g(DynamicFunctionNode.class, null)).initialize(TuningConsumer.CUSTOM_TUNING_ENABLED)` | 낮음 (false면 InputPort가 통과시킴) |
| YUV 처리 | 새 파일 `core2/node/TuningConsumer` (새 smali 클래스) | `TuningConsumer implements DynamicFunctionNode.FunctionConsumer` | `a(ExtraBundle, ImageBuffer)` | HEIC + YUV_420_888일 때만 Y plane LUT in-place | 중 (stride 계산 오류 시 영상 깨짐. 처리 시간 >500ms 경고 가능) |
| ToneMap 변경 | `core2/node/aiIsp/samsung/v1/SecAiIspNode.java` | `SecAiIspNode` | `setGlobalToneMap()` (`:326-334`) | **1차에서는 변경하지 않음.** 값 로그만 추가 (`Arrays.toString(dArr)`) | 로그: 낮음 / 값 변경: **높음** (구조 미확인, vendor crash 가능) |
| YUV dump | 새 파일 `TuningConsumer` | `TuningConsumer` | `dump(...)` (private) | `YUV_DUMP_ENABLED`일 때 A/B를 앱 전용 디렉터리에 raw로 저장 | 낮음 (기본 off, 저장공간 주의) |

수정하지 않는 것: `DynamicFunctionNode.java`, `OriginalDraftNodeChainComposer`, `SecImageCodecNode*`, native `.so`.

---

## 9. 아직 모르는 부분

- HAL 내부 알고리즘 (HDR merge, GTM 생성 방식): **확인 필요**
- `samsung.android.control.globalToneMap`의 길이, 배열 배치, 값 범위, 의미: **확인 필요** (3장의 로그로 확인)
- vendor `BayerAIPhotoWrapperIF`가 GTM curve를 어떻게 쓰는지: **확인 필요**
- `nativePutByteBufferFromImage`의 실제 chroma 순서(NV21/NV12): **확인 필요** (Test A는 chroma를 건드리지 않아 영향 없음)
- 어떤 설정에서 P010(10-bit)이 들어오는지, 실제 rowStride/heightSlice 값: **확인 필요** (logcat `processPicture - Start`)
- HEIC encoder의 색공간(ICC)과 내부 처리: **확인 필요**
- gain map 계산 방식: **확인 필요**
- 재서명한 Samsung Camera APK를 설치하고 실행할 수 있는지 (system app, 플랫폼 서명, 권한): **확인 필요**. prototype 진행 여부를 가장 크게 좌우함
- Apple의 비공개 구현: 분석 범위 밖

---

## 최종 답변

**Q1. DynamicFunctionNode에서 실제 YUV pixel을 수정할 수 있는가?**
예. DynamicFunctionNode는 callback에 `ImageBuffer`(direct ByteBuffer)를 넘기고, **같은 buffer를 다음 노드로 넘깁니다(in-place).** callback에서 바꾼 내용이 그대로 codec에 들어갑니다. 다만 현재 앱에서는 draft 복구용으로만 쓰이고 메인 encoder 앞에는 없으므로, **새 인스턴스를 ENCODER 체인에 추가해야 합니다.**

**Q2. 정확히 어느 코드에 넣어야 하는가?**
`EncoderNodeChainComposer.create()`에서 SecImageCodecNode보다 먼저 `DynamicFunctionNode(TuningConsumer)`를 추가합니다. activate는 `EncoderNodeChainComposer.configure()`에서 하고, 처리 로직은 새 클래스 `TuningConsumer.a()`에 넣습니다.

**Q3. `setGlobalToneMap()`의 curve 구조와 값의 범위는?**
확인된 것: 파라미터 없는 private 메서드이고, HAL CaptureResult `samsung.android.control.globalToneMap`(`double[]`)을 첫 프레임 metadata에서 읽어 native cmd 112 → `SecAiIspNode::setGtmCurveInfo(Array<double>*)` → vendor wrapper로 넘깁니다. 길이 검사는 없습니다.
**point 개수, 배열 배치, 값 범위는 APK 안에서 확인할 수 없습니다(확인 필요).** 3장의 로그로 확인해야 합니다.

**Q4. GlobalToneMap 변경과 최종 YUV 변경 중 prototype에 맞는 것은?**
**최종 YUV 변경**입니다. 결과를 수식으로 검증할 수 있고, HEIF라면 모든 dsMode 결과에 같은 위치에서 적용되며, vendor 라이브러리에 잘못된 입력을 줄 위험이 없습니다. GTM은 AI ISP(RAW 입력) 경로에만 영향을 주고, 구조를 모르며, 효과도 보장되지 않습니다. GTM은 로그로 구조를 먼저 파악한 뒤의 후속 과제입니다.

**Q5. HEIF 경로에서 가장 안전하게 A/B 테스트할 수 있는 위치는?**
ENCODER 체인 첫 노드(SecImageCodecNode 바로 앞)의 `TuningConsumer.a()` 안, LUT 적용 직전(A)과 직후(B)입니다. 같은 호출 안이라 같은 frame이 보장되고, 조건(`ExtraBundle.e == HEIC`, `format == YUV_420_888`)으로 Ultra HDR, JPEG, P010을 제외할 수 있습니다.

**Q6. 첫 prototype에서 수정할 파일과 메서드는?**
1. `EncoderNodeChainComposer.create(CamCapability)`: 노드 추가
2. `EncoderNodeChainComposer.configure(NodeChainCompositionBundle)`: 노드 activate (플래그 연동)
3. 새 클래스 `TuningConsumer`: `a(ExtraBundle, ImageBuffer)`(Y LUT)와 `dump(...)`
4. (선택, 로그만) `SecAiIspNode.setGlobalToneMap()`: curve 값 출력

---

## Confirmed
- EncoderNodeChainComposer 체인 = `SecImageCodecNode → SefNode`. DynamicFunctionNode는 없음
- DynamicFunctionNode는 OriginalDraft(draft JPEG 복구) 체인에만 있고 pixel을 바꾸지 않음
- DynamicFunctionNode는 순수 Java이고, callback에 ImageBuffer를 넘기며, 같은 buffer를 반환함(in-place)
- ImageBuffer는 쓰기 가능한 direct ByteBuffer. YUV_420_888/P010은 `processPictureYuv`로 dispatch됨
- YUV 버퍼 = Y[rowStride×heightSlice] + interleaved chroma. stride는 ImageInfo.StrideInfo
- 체인은 `NodeChain.p()`로 동기 실행되고, 비활성 노드와 체인은 pass-through
- post 체인의 마지막(WATERMARK) 다음에 ENCODER가 연결됨. IPP/PPP/SPP 모두 ENCODER를 configure함
- codec encode 입력은 YUV_420_888 / FLEX_RGBA_8888 / YCBCR_P010. 출력 포맷은 `ExtraBundle.e`
- Ultra HDR(JPEG_R/HEIC_ULTRAHDR)은 별도 YUV(`extraYuvImageForGainMap`)로 gain map을 만듦
- 일반 JPEG(dsMode 0): `takePictureInternal()` → `CamDevice.O()` → HAL JPEG. 처리 체인 없음
- HEIF: `mPictureEncodeFormat == 0x48454943` → `takeSingleProcessingPicture()` → UN_COMP YUV → 처리 체인 → ENCODER
- setGlobalToneMap: 파라미터 없음. `double[]` HAL CaptureResult `samsung.android.control.globalToneMap`. native cmd 112 → `setGtmCurveInfo(Array<double>*)`. 길이 검사 없음
- 기존 노드별 dump는 개발 빌드 + system property가 있어야 동작함

## Likely
- 8-bit YUV의 chroma 순서는 NV21(VU)
- P010은 샘플당 16-bit에 상위 10-bit가 유효함
- GTM curve는 HAL이 해당 장면에 적용한 global tone curve이고, vendor AI ISP가 RAW→YUV rendering에 참고함
- 노드 안에서 buffer를 release하면 안 됨 (ownership은 프로세서에 있음)
- `ExtraBundle.e`는 Sequence의 encode format(HEIF면 1212500294)
- 양산 기기에서는 기존 dump property를 켤 수 없음

## Unknown
- GTM 배열의 길이, 배치, 범위, 의미, vendor 사용 방식
- HAL/vendor 알고리즘 내부
- 실제 rowStride/heightSlice 값과 P010이 쓰이는 조건
- HEIC 색공간(ICC)과 encoder 내부 처리, gain map 계산 방식
- 수정한 APK의 설치·실행 가능성 (서명, system app)
- dump 파일을 adb로 꺼낼 수 있는 경로

## Exact modification targets
1. `EncoderNodeChainComposer.create(CamCapability)` (`EncoderNodeChainComposer.java:60-68`): ImageCodec 앞에 `DynamicFunctionNode(new TuningConsumer())` 추가
2. `EncoderNodeChainComposer.configure(NodeChainCompositionBundle)` (`:32-57`): `initialize(CUSTOM_TUNING_ENABLED)`
3. 새 클래스 `com.samsung.android.camera.core2.node.TuningConsumer`: `a(ExtraBundle, ImageBuffer)`, `dump(...)`, 상수 `CUSTOM_TUNING_ENABLED=false`, `YUV_DUMP_ENABLED=false`
4. (로그만, 선택) `SecAiIspNode.setGlobalToneMap()` (`SecAiIspNode.java:326-334`)

## Prototype plan
1. **사전 확인 (코드 수정 없음)**: 수정한 APK를 설치하고 실행할 수 있는지 먼저 확인. 안 되면 이후 단계는 의미 없음
2. **관찰 빌드**: 플래그는 모두 false. 노드만 추가하고 `TuningConsumer.a()` 첫 줄에 format/size/stride/`ExtraBundle.e` 로그만 남김. HEIF 촬영 시 노드가 호출되는지, 결과물이 원본과 byte 단위로 같은지 확인 (회귀 없음 검증)
3. **dump 빌드**: `YUV_DUMP_ENABLED=true`, `CUSTOM_TUNING_ENABLED=false`. A와 B가 같은지 확인. dump를 NV21로 열어 정상 영상인지, stride 해석이 맞는지 확인
4. **Test A 빌드**: `CUSTOM_TUNING_ENABLED=true`, LUT ×1.10. A/B dump로 `B.Y == LUT[A.Y]`, `B.UV == A.UV` 확인. 최종 HEIC에서 밝기 변화 확인. 처리 시간 로그(<500ms) 확인
5. **장면별 확인**: 일반 / HDR / Night / AI ISP 장면에서 HEIF 촬영. Samsung 처리 결과(A)는 그대로이고 LUT만 추가됐는지 확인
6. **후속 (별도 단계)**: LUT를 tone curve(Test B), highlight roll-off(Test D)로 바꾸기. GTM 로그로 curve 구조 파악. P010과 Ultra HDR 지원 검토
