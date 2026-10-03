# Samsung ↔ Apple 기능 대응표

**주의**: 이 표는 **기능적으로 대응되는 처리 단계**를 나란히 놓은 것입니다. 두 회사의 알고리즘이 같거나 비슷하다는 뜻이 아닙니다. Apple 열은 공개 설명만 담았습니다(`apple-public-pipeline.md`).

수정 가능성 기호: ◎ Java만으로 가능 / ○ Java로 파라미터 조정 가능(native 알고리즘 유지) / △ native .so 수정 필요 / × APK 밖(HAL/ISP/vendor) / ? 확인 필요

---

## 1. 대응표 (요청 형식)

| Image Processing Function | Apple 공개 개념 | Samsung APK 대응 | 실제 클래스 / 메서드 | Native Node / Library | 수정 가능성 |
|---|---|---|---|---|---|
| Multi-frame HDR | Smart HDR | MF_HDR, AEB_HDR, HYBRID_HDR, SS_HDR (dsMode 20-29, 200, 191-243, 150) | `MfHdrNodeChainComposer` → `ArcMfHdrNode`(v1/v2/v4) `processPictureYuv` / `nativeCall(makeMfHdr)`; dsMode는 HAL `dynamicShotHint` | `libhigh_dynamic_range.arcsoft.so`(ARC_HDR_*), `libAEBHDR_wrapper`, `libhybridHDR_wrapper`, `libsame_source_hdr.arcsoft` | ×(알고리즘) / ○(EV list `multiFrameEvList`·ref frame 선택은 Java) / ?(HDR 강도 파라미터) |
| Low-light fusion | Night mode (adaptive bracketing) | SUPER_NIGHT, LL_HDR, HIFI_LLS | `SuperNightPhotoMaker`, `ArcSuperNightNode` v3, `MpiSuperNightNode` v2 (`SET_COLOR_TEMPERATURE`, `SET_GLOBAL_TONE_MAP`), `ArcLlHdrNode`, `MpiHifiLlsNode` | `libsupernight_wrapper_v3`, MPI(`SuperNightSolution_*`), `liblow_light_hdr.arcsoft`, `libMultiFrameProcessing10/20` | ○(GTM 커브·색온도 값을 Java에서 바꿔 전달) / × |
| High-resolution fusion | Photonic Engine 관련 개념(이른 단계 병합) / Deep Fusion | AI_ISP (Bayer NN), AI_HIGH_RES, FUSION_HIGH_RES, TETRA/HEXADECA SR | `SecAiIspNode`(`setGlobalToneMap`, `setEdgeMode`, `setColorTemperature`), `SecAiHighResNode` v3, `ArcFusionHighResNode`, `SecTetraSrNode` | `libbayeraiphoto_wrapper_v1`(추정), `libAIQSolution_MPI*`, `libAIHRWrapper`, `libhigh_res.arcsoft`, `libdtsr_wrapper_v1` | ○(GTM·edgeMode 값) / × |
| Noise reduction | computational NR (multi-frame + sky segmentation 기반) | ISP NR + MF merge + AI 노드 NR(`setNoiseIndex`) | `ArcSRRawNode.setNoiseIndex`, `ArcAiClearZoomNode.setNoiseIndex`, `ArcMacroRawSrNode.setNoiseIndex`; 표준 `NOISE_REDUCTION_MODE` | ISP(HAL), vendor .so | ×(대부분) / ?(noiseIndex는 HAL 메타에서 옴 — Java에서 스케일 가능하지만 효과 확인 필요) |
| Sharpening | detail/texture enhancement (Deep Fusion) | ISP edge + ArcSoft `sharpenIntensity` + SR 노드 `sharpIntensity` | CaptureRequest `samsung.android.edge.mode` / `EDGE_MODE`; `SecAiIspNode.setEdgeMode` | ISP, ArcSoft | ○(edge mode 요청) / △(ArcSoft sharpen 기본값) |
| Tone mapping | HDR tone mapping, richer midtones/deeper shadows | HAL GTM(`globalToneMap`) + **SecLocaltmNode**(LTM) + HDR 노드 내부 | `SecLocaltmNode.processPictureYuv` → `createLocaltmInitParam`(drcRatio, gain, ev, CCT, faces, sceneIndex, personalPresetIndex) ; 각 RAW 노드 `setGlobalToneMap()` | `libLocalTM_wrapper.camera.samsung.so` | ○(GTM double[]·LTM InitParam을 Java에서 조정) / ◎(인코더 직전 Java 톤 커브 추가) |
| Color rendering | image rendering (ML WB/contrast/saturation) | ISP CCM/AWB + ColorTune 필터 + LUT 필터 | `EffectController.getColorTuneFilterInfo`, `ColorTuneProcessor`, `SecFilterNode.processPictureYuv` → `FilterProcessor.h()`; 요청 키 `control.colorTemperature`, `control.wbLevel`, `control.colorSpaceMode` | `SemFilterBufferedProcessor`(framework), `libcamera_effect_processor_jni`(프리뷰), `libMyFilter*` | ◎(필터 문자열·LUT) / ○(WB 요청 키) / × (ISP CCM) |
| Skin tone | semantic processing (skin segmentation, 인물별 skin tone) | Beauty v4, Selfie Tone(`personalPresetIndex`), FaceRestoration, `beautyFaceSkinColor`, 결과 `faceToneWeight` | `SecBeautyNode`(BEAUTY_PROPERTY), `AbstractShootingModePresenter.setSelfieToneMode`, `MakerSettingApplier.setSelfieToneMode`, `SecLocaltmNode`(faces, personalPresetIndex) | `libBeauty_v4`, `libFaceRestoration`, `libLocalTM_wrapper` | ○(preset index·beauty level) / ◎(Java에서 얼굴 Rect 기반 국소 보정 — faces는 메타로 제공) |
| Highlight recovery | HDR / tone mapping, brighter highlights | MF HDR merge + GTM + LTM + Ultra HDR gain map | HDR 노드, `setGlobalToneMap`, `SecImageCodecNode.setGainMapSubImage`(102), `SaveYuvForGainMapNode` | ArcSoft/MPI, Simba encoder | ○(GTM 상단부 커브) / ◎(인코더 직전 roll-off) — gain map 정합성 주의 |
| Shadow rendering | tone mapping (deeper shadows) | GTM 하단부 + LTM(drcRatio) + ColorTune `SL` | `SecLocaltmNode`(drcRatio), `ColorTuneProcessor`(SL=) | `libLocalTM_wrapper` | ○ / ◎ |

---

## 2. 추가 대응 항목

| Function | Apple 공개 개념 | Samsung 대응 | 클래스 | 수정 가능성 |
|---|---|---|---|---|
| Segmentation | person/skin/hair/sky/teeth/glasses panoptic segmentation | dsExtraInfo `NEED_SEMANTIC_MAP`(0x100) 플래그, 보케 노드의 segmap/matte(`PORTRAIT_DATA_PROPERTY_IMAGE_SEGMAP/MATTE`), 프리뷰 SceneDetector | `DynamicShotExtraInfo`, `SecDualBokehNode`/`SingleBokehNode`, `SribSceneDetectionNode` | ?(semantic map 소비처 확인 필요) / ◎(Java에서 자체 분할 모델 추가는 가능하지만 비용 큼) |
| Scene-aware | ML 기반 장면 적응 | Scene Optimizer(`sceneDetectionInfo` → HAL, `sceneIndex` → LTM) | `MakerSettingApplier.setSceneDetectionMode`, `ProcessingPhotoMakerBase.initializeSequence:720` | ○ |
| Frame selection / exposure strategy | adaptive bracketing | HAL이 dsMode·EV list 결정, `multiFrameEvList`, `superNightShotMode` | `SemCaptureRequest`, `SuperNightPhotoMaker` | ×(결정 로직은 HAL) / ○(요청 키 일부) |
| Styles | Photographic Styles (mask 기반, 촬영 시 적용) | ColorTune(Manual color tone), AI My Filter(intensity/temperature/contrast/saturation/grain), LUT 필터 | `EffectController`, `Constants.MANUAL_COLOR_TUNE_SETTING_KEY_LISTS`, `AI_MY_FILTER_SETTING_KEY_LIST` | ◎ (단, Samsung 필터는 **전역** 조정이며 mask 기반이 아님) |
| HDR 표시 | Adaptive HDR / gain map | Ultra HDR (JPEG_R, HEIC_ULTRAHDR, P010) | `SecImageCodecNode`(SET_SUB_IMAGE_FOR_GAIN_MAP, SET_SUB_IMAGE_EV_FOR_GAIN_MAP) | ○(sub image EV) / △ |

---

## 3. 구조 차이 요약

| 관점 | Apple(공개) | Samsung(이 APK) |
|---|---|---|
| 병합 위치 | Photonic Engine: 이른 단계(비압축) | 경로별로 다름: AI ISP·MPI Night은 RAW, MF/LL HDR은 YUV |
| 국소 렌더링 | segmentation mask 기반 (인물·피부·하늘) | LTM(얼굴 Rect, sceneIndex 기반) + Beauty(얼굴) + 보케(segmap). **하늘 mask 기반 처리 근거는 APK에서 찾지 못함 [확인 필요]** |
| 사용자 스타일 | Photographic Styles (파이프라인 내부, mask 기반) | ColorTune/필터(인코더 직전, 전역) |
| 처리 주체 | 비공개 | HAL/ISP + vendor .so, APK는 오케스트레이션 |

## 중간 결과

### Confirmed
- 표의 Samsung 열 클래스와 메서드는 모두 디컴파일 코드나 libnode-jni 심볼로 존재를 확인했습니다.

### Likely
- Apple look과의 차이 가운데 **전역 톤·색(곡선, 채도, WB, 피부 hue)** 은 Samsung 출력 단계(Level 1-2)에서 상당 부분 줄일 수 있습니다. **국소/semantic 차이(하늘 NR, 인물별 tone)** 는 mask가 없으면 재현이 제한됩니다.

### Unknown
- Samsung HAL이 sky/skin semantic map을 내부에서 쓰는지.

### Next Targets
- `NEED_SEMANTIC_MAP` 비트를 HAL이 언제 세우는지 logcat으로 확인하고, semantic map 버퍼가 앱으로 오는지(ExtraBundle 키) 추적.
