# Native Image Processing — JNI / Node / Library 분석

방법: `nm -D`(export 심볼), `readelf -d`(NEEDED), `strings`(dlopen 대상 라이브러리명, 실패 로그, 파라미터 로그), Java `native` 선언과 `System.loadLibrary` 검색. **.so 전체 디스어셈블은 하지 않았습니다.**

---

## 1. JNI 구조 — 핵심은 `libnode-jni.so` 하나

```
Java  com.samsung.android.camera.core2.node.NativeNode            (classes3.dex)
        static { new DynamicLibraryLoader("node-jni").loadAsync(); }
        NativeNode(NodeId id, String tag) → loadNativeNode(id, tag.replace("Node","NativeNode"))
        nativeCall(long ref, int key, long typeConv, long[] getTypeConv, Object[] args)   ← 모든 노드의 공통 진입점
        setNativeCallback(...), staticNativeCall(...), releaseNativeNode(...)
          │  (JNI_OnLoad에서 RegisterNatives; Java_ 접두 export 없음)
          ▼
lib/arm64-v8a/libnode-jni.so  (APK 포함, 1.3MB)
   NEEDED: liblog, libandroid, libm, libdl, libimagexmpinjector.so, libtype-converter.so, libc++_shared
   클래스 등록: "com/samsung/android/camera/core2/node/NativeNode", "...$NativeCallback"
   내부 C++ 클래스: <feature>::<vendor>::v<N>::<Name>Node  (예: N7localtm7samsung4v1_114SecLocaltmNodeE)
   각 Native node가 vendor .so를 dlopen + dlsym  ("fail to get symbol(X) in library(Y)" 로그)
          ▼
/system 또는 /vendor 의 *.camera.samsung.so / *.arcsoft.so  (APK 외부, Manifest uses-native-library)
```

- Java 노드는 `NativeNode.Command<RET>(int key, Class<?>... argTypes)`를 static 필드로 정의하고 `nativeCall(COMMAND, args...)`로 호출합니다. **명령 번호와 인자 타입은 Java에서 모두 보이므로**, native를 고치지 않고도 **어떤 파라미터가 어떤 값으로 넘어가는지 Java에서 가로채거나 바꿀 수 있습니다.** **[확인됨]**
- 노드 구현 선택: `NodeFeatureLoader.doLoad()`가 `SEC_FLOATING_FEATURE_CAMERA_CONFIG_VENDOR_LIB_INFO`를 `<libName>.<vendor>.v<major>_<minor>` 패턴으로 파싱합니다. **[확인됨]**

---

## 2. Node별 분석 (우선 분석 대상)

표 컬럼: Java 클래스(파일) / native 클래스 / vendor 라이브러리(dlopen) / 주요 native command / 이미지 처리 역할 / 확실성

| Node | Java 파일 | native 클래스(libnode-jni) | vendor 라이브러리 | 주요 Command / 심볼 | 역할 | 확실성 |
|---|---|---|---|---|---|---|
| **SecImageCodecNode** | node/imageCodec/samsung/v2/SecImageCodecNode.java | `imageCodec::samsung::v2::SecImageCodecNode` | `libsimba.media.samsung.so`(SimbaEncoderNdkV2/DecoderNdk), `libsimba.cfa.media.samsung.so`, `libimagecodec.quram.so`, `libjpega.camera.samsung.so`, `libexifa.camera.samsung.so`, `libslljpeg.media.samsung.so` | SET_CONFIGURATION(1), SET_SUB_IMAGE_EV_FOR_GAIN_MAP(2), START_ENCODING(3), START_DECODING(5), SET_DS_MODE(6), DISTORTION_APPLIED(7), SET_MAIN_IMAGE(101), SET_SUB_IMAGE_FOR_GAIN_MAP(102), 스테레오(103-107), FRAME_WATERMARK(108); native 심볼 `configureColorSettings`, `setGainMapSubImage`, `executeEncoding`, `makeExifAppSections` | **최종 JPEG/HEIF 인코딩 + Ultra HDR gain map + EXIF** | 확인됨 |
| **SecBeautyNode** v4 | node/beauty/samsung/v4/SecBeautyNode.java | `beauty::samsung::v4::SecBeautyNode` | `libBeauty_v4.camera.samsung.so` | BEAUTY_PROPERTY(0), RESHAPE_PROPERTY(1), CAMERA_PROPERTY(2), PROCESS_BEAUTY_FOR_CAPTURE(102) … | 피부 보정, 얼굴 형태, 조명 | 확인됨 |
| **SecAiHighResNode** v1-v3 | node/aiHighRes/samsung/v*/ | `aiHighRes::samsung::v3::SecAiHighResNode` | `libAIHRWrapper.camera.samsung.so`, `libHREnhancementAPI.camera.samsung.so`, `libAImode_wrapper` | `setLocalTmInfo`, setGlobalToneMap | AI 고해상도 + 내부 LTM | 확인됨(심볼) |
| **ArcAiHighResNode** | (이 버전 APK의 Java에는 해당 이름 없음) | - | - | - | - | 확인 필요: 존재하지 않음 |
| **ArcFusionHighResNode** | node/fusionHighRes/arcsoft/v1/ | `ArcFusionHighResNode` | `libhigh_res.arcsoft.so` (`ARC_LLHDR_*` API) | | Fusion 고해상도 | 확인됨(노드), lib 매핑은 추정 |
| **ArcHybridHdrNode** | node/hybridHdr/arcsoft/v1/ | `Hdr7arcsoft2v116ArcHybridHdrNode` | `libhybridHDR_wrapper.camera.samsung.so` | setGlobalToneMap 사용 | RAW+YUV 하이브리드 HDR | 확인됨 |
| **ArcAebHdrNode** | node/aebHdr/arcsoft/v1/ | `ArcAebHdrNode` | `libAEBHDR_wrapper.camera.samsung.so` | | 노출 브라케팅 HDR | 확인됨 |
| **ArcMfHdrNode** v1/v2/v4 | node/mfhdr/arcsoft/v*/ | `mfhdr::arcsoft::v{1,2,4}::ArcMfHdrNode` | `libhigh_dynamic_range.arcsoft.so` (`ARC_HDR_Init/PreProcess/Process/GetDefaultParam/SetReservedParam/EnableProtect`) | v4: SET_REF_FRAME_INDEX(108), `makeMfHdr`, `setCaptureInfo`, `setFaceInfo` | 주간 multi-frame HDR merge | 노드 확인됨 / lib 매핑은 추정(API명 일치) |
| ArcLlHdrNode v1/v4 | node/llhdr/arcsoft/ | `ArcLlHdrNode` | `liblow_light_hdr.arcsoft.so` (`ARC_LLHDR_SetDRCGain/SetEVValue/SetISPGain/…`) | 로그: `ARC_LLHDR_GetDefaultParam enableFDInside, intensity, lightIntensity, saturation, sharpenIntensity` | 저조도 HDR, 노이즈, **saturation/sharpen 파라미터 보유** | 확인됨(문자열) |
| ArcSsHdrNode | node/sshdr/arcsoft/v1/ | | `libsame_source_hdr.arcsoft.so` (`ARC_SSHDR_*`) | 로그: `ARC_SSHDR_GetDefaultParam … saturation, sharpenIntensity` | same-source HDR | 확인됨 |
| MpiHifiLlsNode | node/hifills/mpi/v1/ | | `libMultiFrameProcessing10/20.camera.samsung.so` (`construct/deconstruct`) | INIT(100) … MAKE_HIFILLS(105) | multi-frame NR | 확인됨 |
| ArcSuperNightNode v3 | node/superNight/arcsoft/v3/ | `superNight::arcsoft::v3` | `libsupernight_wrapper_v3.camera.samsung.so` | `setTuningBuffer`, `initSuperNightLib`, `makeSuperNight` | Night | 확인됨 |
| MpiSuperNightNode v2 | node/superNight/mpi/v2/ | `superNight::mpi::v2` | `SuperNightSolution_*` (MPI 계열, 정확한 lib명은 확인 필요) | SET_COLOR_TEMPERATURE(115), **SET_GLOBAL_TONE_MAP(118)**, SET_YUV_INPUT_DATA(119) | Night (RAW/YUV) | 확인됨 |
| SecAiIspNode | node/aiIsp/samsung/v1/ | `aiIsp::samsung::v1::SecAiIspNode` | `libbayeraiphoto_wrapper_v1.camera.samsung.so`(`__BayerAIPhotoIF_Create`), `libAIQSolution_MPI*.camera.samsung.so` | **SET_GLOBAL_TONE_MAP**, `setGtmCurveInfo`, `setColorTemperature`, `setEdgeMode`, `setSpatialFrequencyResponseData`, `getLightMapInfo` | Bayer AI ISP (RAW→YUV) | 확인됨(심볼), lib 매핑은 추정 |
| **SecLocaltmNode** v1 | node/localtm/samsung/v1/SecLocaltmNode.java | `localtm::samsung::v1_1::SecLocaltmNode` | `libLocalTM_wrapper.camera.samsung.so` (`secLocalTMWrapper_initialize/destroy`) | PROCESS_LOCAL_TM(1)(BufferInfo, Rect, LocaltmInitParam); native `processLocaltm`, `setLocalTmAuxParam`, `setLocalTmFwkParam` | **Local tone mapping / local contrast (single-frame)** | 확인됨 |
| ArcSIENode | node/socialImgEnhance/arcsoft/v1/ | `socialImgEnhance::arcsoft::v1` | `libimage_enhancement.arcsoft.so` (`ARC_IE_Init/Process/GetDefaultParam/SetImgOri`) | `processImgEnhanceImage` | 이미지 향상(SIE_MERGE) | 확인됨 |
| ArcFaceRestoNode | node/faceRestoration/arcsoft/v1/ | | `libFaceRestoration.camera.samsung.so` | | 얼굴 복원 | 확인됨 |
| **SecDualBokehNode** | node/dualBokeh/ | `dualBokeh::samsung::v1_1` | `libDualCamBokehCapture.camera.samsung.so`, `libPortraitSolution` | `setLocalTmInfo/AuxParam/FwkParam` (보케 노드가 LTM 포함) | 인물 보케 + LTM | 확인됨 |
| SecStereoPhotoNode | node/stereoPhoto/ | | `libStereoSolution.camera.samsung.so` | | 스테레오 | 확인됨 |
| SecTetraSrNode | node/tetraSr/samsung/v1/ | | `libdtsr_wrapper_v1` (추정) | setGlobalToneMap | Tetra 리모자이크 SR | 확인됨(노드) |
| SecUdcNode | node/udc/samsung/ | | `libudc_core.camera.samsung.so` | | 언더디스플레이 카메라 복원 | 확인됨 |
| **DngManageNode** | node/DngManageNode.java | `DngManageNativeNode` | (내부) | processPictureYuv/Raw | DNG 저장 | 확인됨 |
| **ExifManageNode** | node/ExifManageNode.java | `ExifManageNativeNode` | `libexifa.camera.samsung.so` | | EXIF | 확인됨 |
| **XMPNode** | node/XMPNode.java | `XMPNativeNode` | `libimagexmpinjector.so`(APK, `injectXMP`, `makeXMPData`) | | XMP 메타 | 확인됨 |

---

## 3. APK에 포함된 .so 역할 확인

| 라이브러리 | Java 연결 | 확인된 역할 | look 영향 |
|---|---|---|---|
| `libnode-jni.so` | `NativeNode` (RegisterNatives) | 모든 처리 노드의 JNI 브리지, vendor lib dlopen | **간접적으로 큼** (파라미터 전달) |
| `libpost_processor_jni.so` | `com.samsung.android.post.effect.CompositingProcessor` (`System.loadLibrary("post_processor_jni")`) → `FrameWatermarkProcessor` | EGL/GLES3 compositing (`compositeElement`, `drawWatermark`, `encodeToMemorySimba`), `libsecimaging_pdk` 사용 | 워터마크/합성. 색조정 근거 없음 |
| `libcamera_effect_processor_jni.so` | `com.samsung.android.camera.effect.SecEffectProcessor / SecFilterBufferedProcessor / SecEffectThumbnailProcessor / SecEffectHalProcessor` | **GLES 셰이더 필터 엔진**: CustomColor(tint/saturate/temperature/contrast/levels) 셰이더, **64³ 3D LUT**(512×512, 8×8 타일) lookup 셰이더, 필름 grain/vignette, MyFilter(`MyFilter_Create`, `libMyFilterPlugin.camera.samsung.so`, `/data/DownFilters/MyFilter/`), filterprovider LUT(`com.samsung.android.provider.filterprovider,lib*.so`), 스킨 스무딩 셰이더 | **프리뷰/썸네일 필터. 캡처 필터는 `SemFilterBufferedProcessor`(framework)가 담당** |
| `libSceneDetectorJNI.so` | `vizinsight.atl.vzimageclassifier.VZClassifier` (`Java_*` export 13개) → `SribSceneDetectionNode` | 프리뷰 장면 분류 → sceneIndex | 간접 (HAL·LTM이 장면별 튜닝) |
| `libimageutils-jni.so` | `core2.util.ImageUtils` | `nativeConvertFlexibleYuv420888ToPackedNV21`, `nativeConvertYCbCrP010ToPackedYCbCrP010`, `nativeConvertPackedNV21ToRGBA_Partial`, `nativeBlendWatermark` | 포맷 변환만 |
| `libquramresizer-jni.so` | `core2.util.QuramResizer` | `nativeQuramResizeNV21ToPackedNV21`, `…ToRGBA` | 리사이즈(썸네일) |
| `libimagexmpinjector.so` | libnode-jni NEEDED | `injectXMP`, `makeXMPData` | 메타데이터 |
| `libtype-converter.so`, `libdirectbuffer-jni.so`, `libnativeutils-jni.so` | JNI 유틸 | 타입 변환, direct buffer | 없음 |
| `libpanorama.arcsoft.so`, `libPanoramaInterface_arcsoft.so`, `libatomjpeg_panorama_enc.quram.so` | `com.samsung.android.panorama.InterfaceNative` | 파노라마 | Photo 무관 |
| `libhandgesture.arcsoft.so` | 손동작 | 셀피 제스처 | 무관 |

---

## 4. 파라미터 수준에서 native에 들어가는 look 관련 값 (Java에서 확인 가능)

| 값 | 출처 | 전달 노드 | Java 위치 |
|---|---|---|---|
| `globalToneMap` (double[]) | HAL 결과 `samsung.android.control.globalToneMap` | SecAiIsp, MpiSuperNight v2, SecTetraSr, SecHexadecaSr, SecAiHighRes, ArcAiClearZoom v2, ArcMacroRawSr, ArcHybridHdr base | 각 노드 `setGlobalToneMap()` |
| `drcRatio`, `captureTotalGain`, `captureEv`, `colorTemperature`, `sunDetectionInfo`, `specialSceneAe`, faces, `sceneIndex`, `personalPresetIndex`, `personalizeParams`, `lightCondition`, `brightnessValue` | HAL 결과 메타 + ExtraBundle | SecLocaltmNode | `SecLocaltmNode.createLocaltmInitParam()` :72 |
| `colorTemperature` | HAL | MpiSuperNight(115), SecAiIsp | `setColorTemperature` |
| `edgeMode` | CaptureRequest `edge.mode` | SecAiIsp | `setEdgeMode` |
| Beauty level, skin color | MakerPrivateKey / `beautyFaceSkinColor` | SecBeautyNode | BEAUTY_PROPERTY |
| Codec quality, output format, stride | `CodecConfiguration` | SecImageCodecNode | `EncoderNodeChainComposer.configure()` |
| Filter 문자열 `customcolor,TE=..,TI=..,CO=..,SA=..,HL=..,SL=..` | `EffectController` / `ManualColorTuneMenuPresenter` | SecFilterNode → SemFilterBufferedProcessor | `ColorTuneProcessor.f()`, `FilterProcessor` :92 `setFilterParameter` |

---

## 중간 결과

### Confirmed
- APK의 native 영상처리 진입점은 `libnode-jni.so` 하나이며, 실제 알고리즘은 vendor .so(APK 밖)에 있습니다.
- 요청한 노드 대부분(SecImageCodec, SecBeauty, SecAiHighRes, ArcFusionHighRes, ArcHybridHdr, ArcAebHdr, ArcMfHdr, SecDualBokeh, SecStereoPhoto, SecTetraSr, SecUdc, DngManage, ExifManage, XMP)이 존재합니다. **`ArcAiHighResNode`는 이 버전에 없습니다.**
- `libpost_processor_jni.so`는 워터마크 합성, `libcamera_effect_processor_jni.so`는 프리뷰/썸네일 GL 필터(CustomColor, 3D LUT, MyFilter)입니다.
- ArcSoft HDR 계열 API에 `saturation`, `sharpenIntensity`, `intensity`, `lightIntensity` 파라미터가 있습니다(로그 문자열).

### Likely
- `ArcMfHdrNode` ↔ `libhigh_dynamic_range.arcsoft.so`, `ArcLlHdrNode` ↔ `liblow_light_hdr.arcsoft.so`, `ArcFusionHighResNode/ArcHighResNode` ↔ `libhigh_res.arcsoft.so`, `SecAiIspNode` ↔ `libbayeraiphoto_wrapper_v1` (API 이름 일치 기준).
- 캡처 경로 COLOR_TUNE 연산은 프리뷰 CustomColor 셰이더와 같은 수식일 가능성이 큽니다(프리뷰·캡처 일치가 필요하므로).

### Unknown
- `SemFilterBufferedProcessor`(`com.samsung.android.camera.filter`)의 실제 구현 위치와 알고리즘.
- vendor lib 내부 튜닝 데이터(`setTuningBuffer`의 ISP 튜닝 버퍼 포맷).
- ArcSoft `GetDefaultParam` 이후 libnode-jni가 `saturation/sharpenIntensity`를 덮어쓰는지 여부.

### Next Targets
- `libnode-jni.so`: `ArcLlHdrNode::nativeProcess`, `ArcMfHdrNode::makeMfHdr` 주변만 Ghidra로 부분 디스어셈블해서 GetDefaultParam 이후 필드 대입 확인.
- 실기기 `/system/lib64`, `/vendor/lib64`에서 `libLocalTM_wrapper.camera.samsung.so`의 export와 문자열(파라미터 이름) 수집.
- 실기기 framework에서 `com.samsung.android.camera.filter.SemFilterBufferedProcessor` 위치 확인.
