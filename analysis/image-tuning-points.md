# Image Tuning Points — 결과 look을 바꿀 수 있는 실제 코드 위치

목표 구조:
```
Samsung Sensor → Samsung Camera Engine(HAL/ISP) → Samsung HDR / Night / Multi-frame / AI (vendor .so)
  → [Custom Image Rendering / Tuning Layer]  ← 이 문서의 대상
  → Apple-like image characteristics → JPEG / HEIF
```

---

## 1. 결론부터: Tuning Layer를 넣을 정확한 위치

**1순위 후보 — ENCODER 체인의 `SecImageCodecNode` 바로 앞**

| 항목 | 내용 |
|---|---|
| 파일 | `sources/com/samsung/android/camera/core2/processor/nodeController/composer/EncoderNodeChainComposer.java` (classes3.dex) |
| 메서드 | `create(CamCapability)` (:59-68), `configure(NodeChainCompositionBundle)` (:31-56) |
| 삽입할 노드 | 기존 클래스 `core2/node/DynamicFunctionNode.java`(Java 전용, in-place 콜백 노드) 또는 새 `Node` 서브클래스 |
| 들어오는 데이터 | 모든 Samsung 처리(HDR/Night/AI ISP/LTM/Beauty/Filter/Watermark)가 끝난 **최종 YUV(ImageBuffer, direct, NV21 + stride)** |
| 이유 | ① 모든 multi-frame·AI 결과가 이미 합쳐진 뒤라 Samsung 파이프라인을 건드리지 않습니다. ② `NodeControllerBase` 생성자에서 등록되므로 **IPP(`IppNodeController`)와 PPP(`PppNodeController`)가 같은 Composer를 씁니다** → 한 곳만 고치면 즉시 처리와 백그라운드 처리 모두 적용됩니다. ③ native 수정이 없습니다. |
| 확실성 | 체인 구조와 공유 여부는 **확인됨**. 실제 삽입 후 동작(포트 연결, 버퍼 수명)은 **실기기 검증 필요** |

**필수 전제 — 경로 A(HAL JPEG 직출력)를 경로 B(YUV→앱 인코딩)로 바꿔야 합니다**
- dsMode==0 + JPEG 저장이면 앱 노드를 전혀 거치지 않습니다(`capture-flow.md` 3.2).
- 코드 수정 없이 바꾸는 법: **저장 포맷을 HEIF로 설정** → `ProcessingPhotoMakerBase.isExtraPostProcessCondition()`이 `mPictureEncodeFormat == 0x48454946('HEIF')`로 `true` → `AutoPhotoMaker.takePicture`가 `takeSingleProcessingPicture`(YUV)를 탑니다. **[확인됨: 코드 경로]**
- 코드로 바꾸는 법(JPEG 유지): `ProcessingPhotoMakerBase.isExtraPostProcessCondition` (:743)이 실험 플래그가 켜졌을 때 `true`를 반환하게 합니다. 이때 HAL에 `EXTRA_POST_PROCESS(0x200000)` 비트가 함께 전달됩니다(`AutoPhotoMaker.getDsExtraInfo` :332). **HAL 출력이 달라지는지는 확인 필요**

---

## 2. 레벨별 개입 지점

### Level 1 — Java/Kotlin image post-processing

| # | 위치 (파일:라인) | 클래스.메서드 | 할 수 있는 것 | 접근 | APK 수정 난이도 | 위험 | 기존 기능 보존 | 예상 효과 |
|---|---|---|---|---|---|---|---|---|
| L1-1 | composer/EncoderNodeChainComposer.java:59 | `create()` + `configure()` | `DynamicFunctionNode` 추가 → 최종 YUV에 tone curve(1D LUT on Y), chroma gain, WB 오프셋, 피부 hue 보호 | 가능 | 중 (smali 패치 1~2 클래스) | 중: 처리 시간 증가, P010/gain map 경로 오동작 가능 | **높음** (앞단 그대로) | 전역 톤·채도·WB·피부색 → **Apple 대비 전역 차이의 대부분** |
| L1-2 | node/filter/SecFilterNode.java:73 | `processImage()` | 이미 YUV를 `byte[]`로 복사하는 지점. 필터 결과 뒤에 후처리 | 가능 | 중 | **높음: 필터 경로는 LTM·gain map을 끔** | 낮음 | 필터 사용 시에만 동작 |
| L1-3 | node/filter/processor/ColorTuneProcessor.java:13 / engine/EffectController.java:267-285 | `f()`, `getColorTuneFilterInfo()`, `getColorTuneParameterString()` | `customcolor,TE=,TI=,CO=,SA=,HL=,SL=` 값을 고정 프리셋으로 강제 | 가능 | **낮음** (문자열·정수 상수) | 중: 필터 비트로 **LTM 해제** | 중 | 전역 색온도·틴트·대비·채도·하이라이트·섀도 |
| L1-4 | (설정만) Manual color tone, AI My Filter, LUT 필터 | `Constants.MANUAL_COLOR_TUNE_SETTING_KEY_LISTS`, `AI_MY_FILTER_SETTING_KEY_LIST`, `FilterProcessor.d()`(파일 기반 .sel) | **APK 수정 없이** 프리셋/LUT 실험 | 가능 | **없음** | 낮음 | 중(LTM 해제) | 빠른 방향성 검증용 |
| L1-5 | composer/DraftEncoderNodeChainComposer.java | `create()` | PPP의 draft(먼저 보이는 임시본)에도 같은 처리 | 가능 | 중 | 낮음 | 높음 | draft와 최종본 look 일치 |

### Level 2 — JNI parameter / processing configuration (native 알고리즘은 그대로, 넘기는 값만 변경)

| # | 위치 | 클래스.메서드 | 바꾸는 값 | 접근 | 난이도 | 위험 | 보존 | 예상 효과 |
|---|---|---|---|---|---|---|---|---|
| L2-1 | node/aiIsp/samsung/v1/SecAiIspNode.java:326; superNight/mpi/v2/MpiSuperNightNode.java:445; tetraSr/samsung/v1/SecTetraSrNode.java:~190; hexadecaSr/samsung/v1/SecHexadecaSrNode.java:~185; aiHighRes/samsung/SecAiHighResNodeBase; aiClearZoom/arcsoft/v2; macroRawSr/arcsoft/v1 | `setGlobalToneMap()` | HAL이 준 `double[] globalToneMap`을 **Java에서 재매핑**(highlight roll-off, midtone, shadow) 후 `nativeCall(SET_GLOBAL_TONE_MAP, …)` | 가능 | 중 | 중~높음: **커브 형식이 확인되지 않음** | 높음 | RAW 경로(AI ISP·Night·SR)의 톤을 **렌더링 단계에서** 바꿈 → 8-bit 후처리보다 품질 손실이 적음 |
| L2-2 | node/localtm/samsung/v1/SecLocaltmNode.java:72 | `createLocaltmInitParam()` | `drcRatio`, `ev`, `personalPresetIndex`, `personalizeParams`, `sceneIndex` 조정 | 가능 | 중 | 높음: native 해석 미상 | 높음 | local contrast, shadow lift 강도 |
| L2-3 | MakerSettingApplier.setSelfieToneMode / `MakerPublicKey.f5710b0`(`samsung.android.control.personalPresetIndex`) | | 후면에도 preset index 적용 시험 | 가능 | 낮음 | 중: HAL이 무시하거나 오동작할 수 있음 | 높음 | HAL+LTM 톤 프리셋 (셀피에서는 이미 사용 중) |
| L2-4 | node/aiIsp/samsung/v1/SecAiIspNode | `setEdgeMode`, `setColorTemperature` | edge mode, CCT 값 | 가능 | 중 | 중 | 높음 | AI ISP 경로 sharpening/WB |
| L2-5 | node/imageCodec/samsung/v2/SecImageCodecNode | `NATIVE_COMMAND_IMAGE_CODEC_SET_SUB_IMAGE_EV_FOR_GAIN_MAP`(2), `CodecConfiguration.quality` | HDR gain map 강도, JPEG 품질 | 가능 | 낮음~중 | 중 | 높음 | HDR 표시 시 highlight 인상, 압축 아티팩트 |

### Level 3 — Samsung native post-processing library

| # | 대상 | 내용 | 접근 | 난이도 | 위험 | 보존 | 효과 |
|---|---|---|---|---|---|---|---|
| L3-1 | `libnode-jni.so` (APK 포함) | ArcSoft `*_GetDefaultParam` 뒤의 `intensity/lightIntensity/saturation/sharpenIntensity` 덮어쓰기 패치 | 가능(바이너리 패치) | **높음** (ARM64 디스어셈블) | 높음 | 중 | HDR/저조도 노드 내부 채도·샤픈 |
| L3-2 | vendor `libLocalTM_wrapper`, `libhigh_dynamic_range.arcsoft` 등 | 알고리즘·튜닝 테이블 | **APK 밖** (시스템 파티션) | 매우 높음 | 매우 높음 | 낮음 | 큼 |

### Level 4 — Camera HAL / ISP configuration

| # | 대상 | 내용 | 접근 | 난이도 | 위험 | 보존 | 효과 |
|---|---|---|---|---|---|---|---|
| L4-1 | CaptureRequest 키: `EDGE_MODE`, `NOISE_REDUCTION_MODE`, `samsung.android.edge.mode`, `control.colorTemperature`, `control.wbLevel`, `control.colorSpaceMode`, `control.specialImageQualityPolicy` | Java에서 값 전달은 가능. **HAL이 Auto 모드에서 존중하는지 확인 필요** | 부분 가능 | 낮음~중 | 중 | 중 | ISP NR/샤픈/WB |
| L4-2 | HAL 튜닝 파일, ISP 3A | APK 밖 | 불가(root 필요) | 매우 높음 | 매우 높음 | 낮음 | 매우 큼 |

### Level 5 — Sensor / ISP firmware
- 접근 불가에 가깝습니다(서명된 펌웨어). 위험이 매우 크고 목표와 맞지 않습니다. **대상에서 제외합니다.**

**권장**: Level 1(L1-1) → Level 2(L2-1, L2-5) 순서로 진행합니다. Level 3 이하는 1·2단계로 한계가 확인된 뒤에만 고려합니다.

---

## 3. 7단계 — 결과 특성별 변경 가능 단계

| 특성 | 세부 | 바꿀 수 있는 단계 (우선순위 순) |
|---|---|---|
| **Color** | white balance / 색온도 / tint | L1-1(UV 오프셋, 회색 기준 보정) · L1-3(TE/TI) · L4-1(`colorTemperature`, `wbLevel`) · L2-4 |
| | saturation / hue | L1-1(chroma gain, hue별 gain) · L1-3(SA) · L3-1(ArcSoft saturation) |
| | skin tone | L1-1(얼굴 Rect: `ExtraBundle`/메타의 faces 기반 국소 hue·chroma 보정) · L2-3(personalPresetIndex) · Beauty |
| | green/magenta bias | L1-1(V/U 축 오프셋) · L1-3(TI) |
| **Dynamic Range** | highlight roll-off | L2-1(GTM 상단, RAW 경로) · L1-1(Y LUT 상단 shoulder) |
| | shadow lifting | L2-2(drcRatio/LTM) · L2-1(GTM 하단) · L1-1(toe) |
| | midtone contrast | L1-1(Y LUT 기울기) · L1-3(CO) |
| | HDR strength | HAL(dsMode·EV list) × · L2-2 · L2-5(gain map) |
| | local contrast | L2-2(LTM) · L1-1(저주파 unsharp, 비용 큼) |
| **Detail** | sharpening / edge | L4-1(edge mode) · L2-4 · L3-1 |
| | micro-contrast / texture | L1-1(중주파 강조 — 프로토타입 1차에서는 제외 권장) |
| | ringing / halo | 원인은 ISP·ArcSoft sharpen → L4-1/L3-1. L1에서는 **줄이기 어렵습니다**(이미 생긴 halo) |
| **Noise** | luminance / chroma noise | ISP·MF merge가 대부분 결정(×). L1-1에서 chroma NR(UV 저역통과)은 가능 |
| | texture destruction | ISP NR 강도(L4-1 `NOISE_REDUCTION_MODE`) — HAL 존중 여부 확인 필요 |
| | temporal NR | multi-frame merge(×) |
| **Low light** | exposure strategy / frame 수 | HAL(×) · `superNightShotMode`, `multiFrameEvList` 요청 키(○, 범위 제한) |
| | highlight protection / shadow brightness / color | L2-1(MpiSuperNight GTM) · L2-4(CCT) · L1-1 |
| **Computational rendering** | sky / skin / face / subject / background | face: 메타의 faces 사용 가능(L1-1/L2-2). sky/subject segmentation: **APK에 일반 사진용 mask 없음 [확인 필요: NEED_SEMANTIC_MAP]** → L1에서 새 분할 모델을 넣어야 함(비용 큼) |

---

## 4. 유지해야 할 것 / 바꿔야 할 것

**그대로 유지 (손대지 않음)**
- HAL 3A, dsMode 결정(장면 판단), multi-frame 정렬·병합: MF_HDR, LL_HDR, HIFI_LLS, SUPER_NIGHT, AI_ISP, AI_HIGH_RES, SR 계열
- RAW 디모자이크·AI ISP 노드의 NR·디테일 복원
- Beauty, FaceRestoration, 보케, distortion correction
- SecImageCodecNode 인코더, EXIF/XMP/SEF 메타데이터, 저장 경로

**바꿀 가능성이 높은 단계**
1. 최종 global tone curve(highlight shoulder, midtone, shadow toe) — L1-1 → 이후 L2-1
2. 전역 채도와 hue별 채도(특히 녹색·청색 과채도 억제) — L1-1
3. WB 경향(CCT·Duv)과 green/magenta — L1-1
4. 피부 hue/chroma 안정화(얼굴 영역) — L1-1(faces)
5. LTM 강도(그림자 과도 리프트 억제) — L2-2
6. Sharpening/edge halo — L4-1 → L3-1 (Java 후처리로는 한계)

---

## 5. 첫 번째 Prototype 설계 (최소 변경)

### Phase 0 — APK 수정 없이 방향 검증 (1~2일)
1. 설정: 저장 포맷 **HEIF**(경로 B 강제), Ultra HDR(HDR10+/JPEG_R) **끄기**, 워터마크·필터 끄기.
2. 같은 장면을 iPhone과 Samsung으로 촬영합니다(삼각대, 같은 화각, 차트 + 실사).
3. 오프라인(Python)에서 Samsung 결과에 Y tone LUT, chroma gain, WB 오프셋, 피부 보호를 적용하며 iPhone 결과와 수치를 비교합니다(8장 지표).
4. 필요하면 Samsung의 기존 **Manual color tone / AI My Filter / LUT 필터**로 같은 프리셋을 재현해 봅니다(L1-4). 이 방법은 LTM이 꺼지는 부작용이 있으므로 참고용으로만 씁니다.
→ 산출물: `tuning_v0.json` (1D tone LUT 256점, chroma gain, UV offset, skin 파라미터)

### Phase 1 — APK 최소 패치: "AppleLikeRenderNode" (smali, 2개 클래스)
```
EncoderNodeChainComposer.create():
    nodeChain.b(new DynamicFunctionNode(consumer), DynamicFunctionNode.class, "appleLikeRender", PORT_TYPE_PICTURE)   // ← 추가, ImageCodecNode보다 먼저
    nodeChain.b(imageCodecNodeBase, ImageCodecNodeBase.class, null, portType)                                       // 기존
    nodeChain.b(sefNode, SefNode.class, null, portType)                                                             // 기존
EncoderNodeChainComposer.configure():
    if (flag) ((DynamicFunctionNode) nodeChain.g(DynamicFunctionNode.class, "appleLikeRender")).initialize(true)
consumer.a(ExtraBundle eb, ImageBuffer buf):
    if format != YUV_420_888(NV21 8-bit) → return            // P010/JPEG_R/RAW는 1차에서 건드리지 않음
    ByteBuffer bb = buf.rentByteBuffer();                     // DirectBuffer API (DirectBuffer.java:186)
    stride = buf.getImageInfo().getStrideInfo() (rowStride, heightSlice)
    (선택) 원본 NV21을 앱 files/.dump/에 저장 → 같은 프레임 A/B 비교
    Y plane:  y' = LUT_Y[y]                                   // tone curve
    VU plane: (u,v) → WB 오프셋 → chroma gain(hue별) → 피부 hue 범위 보호
    buf.returnByteBuffer(bb)
```
- **같은 촬영본으로 A/B를 만드는 방법**: 노드 안에서 처리 전 NV21을 덤프하고 결과는 정상 저장합니다. 원본 YUV는 오프라인에서 같은 인코더 설정으로 JPEG로 만들어 비교합니다. 프레임 차이가 없는 순수 렌더링 비교가 됩니다.
- **실험 플래그**: `android.os.SystemProperties` 대신 앱 설정 키나 파일 존재 여부(`/sdcard/Android/data/.../tuning_on`)로 켜고 끕니다. 기본값은 OFF입니다.
- **커버리지**: IPP와 PPP 모두 적용됩니다(Composer 공유). draft 이미지와 썸네일은 이 단계에 포함되지 않습니다(L1-5와 `AutoPhotoMaker.mThumbnailFilterNode`는 별도).
- **성능**: 12MP NV21 기준 Y LUT(12M 연산)+UV(6M) — Java 루프로 수백 ms 예상 [추정]. APK에 이미 `com.google.android.renderscript.Toolkit`(`librenderscript-toolkit.so`)이 들어 있고 `nativeLut`, `nativeLut3d`, `nativeColorMatrix`, `nativeYuvToRgb`를 제공합니다 **[확인됨]**. 다만 Lut/Lut3d는 RGBA 입력이고 RGB→YUV 역변환이 없으므로, Phase 1은 NV21에서 직접 Y LUT와 UV 연산을 하는 편이 단순합니다. 3D LUT(Phase 0 결과)를 그대로 쓰려면 YUV→RGBA→Lut3d→YUV 변환 비용을 측정해야 합니다.

### Phase 2 — RAW 경로 톤 (L2-1)
1. `SecAiIspNode.setGlobalToneMap`, `MpiSuperNightNode.setGlobalToneMap`에 **로그만** 추가해서 `double[]` 길이·값을 수집하고 커브 형식을 파악합니다.
2. 형식을 확인한 뒤 Phase 0의 tone LUT를 GTM 공간으로 옮겨 재매핑합니다.
3. 같은 장면에서 Phase 1(8-bit 후처리)과 화질(밴딩·노이즈 증폭)을 비교합니다.

### 위험 및 제약 (반드시 확인)
- **서명 문제**: Samsung Camera는 플랫폼 서명된 시스템 앱입니다. 다시 서명한 APK는 signature 권한, 벤더 태그 접근, `uses-native-library` 로딩 조건이 깨질 수 있습니다. **설치·실행 가능 여부를 먼저 확인해야 합니다**(root/Magisk systemless 교체가 필요할 가능성이 큼).
- 필터 계열(L1-2~L1-4)은 `NEED_LTM`을 해제하므로 비교 실험이 오염됩니다. Phase 1은 필터를 **꺼둔 상태**에서 진행합니다.
- Ultra HDR(JPEG_R/P010)에서는 base 이미지만 바꾸면 gain map과 어긋날 수 있습니다. 1차에서는 8-bit만 다룹니다.
- jadx 출력은 재컴파일할 수 없습니다. 실제 패치는 apktool/baksmali smali 수준에서 해야 합니다.

---

## 6. 가장 먼저 실험할 클래스와 메서드 (정확한 목록)

| 순서 | 클래스 | 메서드 | 목적 |
|---|---|---|---|
| 1 | `com.samsung.android.camera.core2.processor.nodeController.composer.EncoderNodeChainComposer` | `create(CamCapability)`, `configure(NodeChainCompositionBundle)` | Tuning Layer 삽입 |
| 2 | `com.samsung.android.camera.core2.node.DynamicFunctionNode` | 생성자 `(FunctionConsumer)`, `processPictureYuv` | in-place 처리 컨테이너(재사용) |
| 3 | `com.samsung.android.camera.core2.maker.ProcessingPhotoMakerBase` | `isExtraPostProcessCondition(CaptureResult, CamCapability)` | JPEG 저장에서도 경로 B 강제(HEIF 설정으로 대체 가능) |
| 4 | `com.samsung.android.camera.core2.util.DirectBuffer` | `rentByteBuffer()`, `returnByteBuffer()` | 버퍼 접근 |
| 5 | `com.samsung.android.camera.core2.node.aiIsp.samsung.v1.SecAiIspNode` | `setGlobalToneMap()` | GTM 형식 로깅 → Phase 2 |
| 6 | `com.samsung.android.camera.core2.node.superNight.mpi.v2.MpiSuperNightNode` | `setGlobalToneMap(CaptureMetadata)` | Night 경로 GTM |
| 7 | `com.samsung.android.camera.core2.node.localtm.samsung.v1.SecLocaltmNode` | `createLocaltmInitParam(...)` | LTM 강도 실험 |

---

## 7. 이미지 비교 실험에서 측정할 수치

**조건**: 삼각대, 같은 화각(크롭·정합), 같은 노출 조건 기록, 장면당 5장 이상, iPhone과 Samsung(원본/튜닝본) 3개 세트

| 범주 | 지표 | 측정 방법 / 차트 |
|---|---|---|
| 색 정확도 | ΔE2000 평균·최대 (24패치) | X-Rite ColorChecker Classic, CIELAB(D65) |
| 화이트밸런스 | 회색 패치 a*, b*; CCT; **Duv**(green/magenta) | 회색 패치 / 18% 그레이 카드, 장면별(주광·텅스텐·혼합) |
| 채도 | 패치별 C*ab 비율(대상/기준), hue angle 오차 Δh | ColorChecker; 실사에서는 hue bin별 평균 채도 |
| 피부 | 피부 패치와 얼굴 영역의 L*, C*, h°, 얼굴 간 분산 | ColorChecker 2·1번 패치 + 실제 인물(다양한 피부톤), 얼굴 검출 마스크 |
| 톤 커브 | OECF(입력 반사율→출력 L*), 18% 그레이 L*, 기울기(감마) | 그레이스케일 스텝 차트(예: 20-step) |
| 하이라이트 | 클리핑 비율(%, Y≥250), shoulder 시작점, 하이라이트 영역 디테일(국소 표준편차) | 역광·하늘 장면 |
| 섀도 | 그림자 영역 평균 L*, 클리핑(Y≤5)%, 섀도 SNR | 동일 장면 |
| Dynamic range | 유효 DR(stops, SNR≥1 기준) | 투과형 DR 차트 또는 다중 노출 스텝 |
| Local contrast | 대역별 RMS contrast, halo 폭(엣지 주변 밝기 오버슈트 px) | 고대비 엣지, 실사 |
| Sharpness | MTF50, MTF10(cy/px), acutance | ISO 12233 slanted-edge |
| Sharpening artifacts | edge overshoot/undershoot %, ringing 폭 | slanted-edge 프로파일 |
| Texture | Dead-leaves texture MTF / texture acutance | Dead leaves 차트(IEEE P1858 CPIQ) |
| Noise | 휘도 SNR, chroma noise σ(a*), σ(b*), visual noise(ISO 15739) | 균일 패치, 저조도(1~10 lux) |
| Low light | 1/5/10 lux에서 18% 그레이 L*, SNR, ΔE, 모션 블러 비율 | 조도계로 통제 |
| HDR 표시 | gain map 최대 boost(stops), HDR/SDR 하이라이트 휘도 비 | Ultra HDR on/off 별도 측정 |
| 일관성 | 같은 장면 연속 촬영의 L*/a*/b* 표준편차 | 10장 연속 |
| 전체 유사도 | iPhone 대비 정합 이미지의 평균 ΔE2000, 휘도 히스토그램 거리(EMD), 채도 분포 거리 | 실사 장면 세트 |
| 처리 비용 | Tuning Layer 처리 시간(ms), 셔터→저장 시간 | logcat 타임스탬프 |

---

## 8. 최종 질문에 대한 답변

**1. 최종 사진의 rendering/look을 결정하는 핵심 처리 지점은 어디인가?**
- 1순위는 **HAL/ISP**(CCM, AWB, GTM, ISP NR/sharpen)입니다. 경로 A(주간 SINGLE + JPEG)는 HAL이 전부 결정합니다. **[확인됨]**
- 앱 쪽에서는 ① multi-frame/AI 노드(RAW→YUV 렌더, `globalToneMap` 사용) ② `SecLocaltmNode`(local tone) ③ `SecFilterNode`(사용자 색조정) ④ `SecImageCodecNode`(인코딩, gain map)입니다. **[확인됨]**

**2. 그대로 유지해야 할 Samsung pipeline은?**
- dsMode 결정, multi-frame 정렬·병합(MF_HDR, LL_HDR, HIFI_LLS, SUPER_NIGHT), AI ISP/AI HighRes/SR, Beauty/FaceRestoration, 인코더와 메타데이터. 노이즈·디테일·DR 확보는 Samsung 엔진이 맡게 둡니다.

**3. iPhone과 비슷한 결과를 만들려면 바꿀 가능성이 높은 단계는?**
- 최종 global tone curve(하이라이트 roll-off, 미드톤, 섀도), 전역·hue별 채도, WB 경향(CCT/Duv), 피부 hue 안정화, LTM 강도, sharpening halo. 앞의 4개는 출력 단계에서 바꿀 수 있고, 뒤의 2개는 파라미터·HAL 레벨이 필요합니다.

**4. Java/Kotlin만 수정해서 가능한 범위는?**
- 최종 YUV 전역 처리(톤 LUT, 채도, WB, 얼굴 Rect 기반 피부 보정, chroma NR) — L1-1
- native에 넘기는 값 변경: `globalToneMap` 재매핑, LTM InitParam, preset index, edge mode, CCT, gain map EV, 코덱 품질 — L2
- 경로 강제(HEIF 또는 `isExtraPostProcessCondition`)
- **불가능한 것**: ISP 단계 NR/샤픈 알고리즘, multi-frame 병합 방식, sky/subject semantic mask(새 모델을 넣지 않는 한)

**5. native .so를 수정해야 할 가능성이 높은 부분은?**
- ArcSoft HDR/저조도 노드 내부 채도·샤픈 기본값(`libnode-jni.so`에서 `GetDefaultParam` 후 덮어쓰기), halo를 만드는 sharpening, LTM 알고리즘 자체, AI ISP 렌더링. 이 중 APK 안에서 패치할 수 있는 것은 **`libnode-jni.so`뿐**이고 나머지는 시스템 파티션입니다.

**6. 가장 먼저 수정·실험할 클래스와 메서드는?**
- `EncoderNodeChainComposer.create()` / `configure()`에 `DynamicFunctionNode` 삽입(6장 1~4번), 그리고 `SecAiIspNode.setGlobalToneMap()` 로깅(6장 5번).

**7. 최소 변경으로 테스트할 첫 prototype 구성은?**
- 5장 Phase 0(HEIF 설정 + 오프라인 튜닝) → Phase 1(Encoder 앞 DynamicFunctionNode, 8-bit NV21 전용, 플래그 기본 OFF, 원본 YUV 덤프로 같은 프레임 A/B). Samsung 처리는 하나도 제거하지 않습니다.

**8. 이미지 비교 실험에서 측정할 수치는?**
- 7장 표: ΔE2000, 회색 a*/b*·CCT·Duv, 채도 비율·Δh, 피부 L*C*h, OECF·미드그레이 L*, 하이라이트/섀도 클리핑, DR, local contrast·halo, MTF50/10, overshoot, texture MTF, 휘도/색 노이즈, 저조도 SNR·L*, HDR boost, 일관성, 전체 ΔE/히스토그램 거리, 처리 시간.

**최종 판단**: 목표 구조(`Samsung Engine → [Custom Tuning Layer] → JPEG/HEIF`)는 **구현할 수 있습니다.** 정확한 위치는 `EncoderNodeChainComposer`의 `SecImageCodecNode` 앞입니다(IPP·PPP 공통). 다만 ① 경로 A(HAL JPEG)를 경로 B로 돌려야 하고 ② 시스템 앱 서명 문제로 설치 가능성을 먼저 확인해야 하며 ③ 전역 처리로는 Apple의 segmentation 기반 국소 렌더링(하늘 NR, 인물별 tone)까지 재현하기는 어렵습니다.
