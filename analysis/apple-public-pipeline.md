# Apple Computational Photography — 공개 자료 기준 정리

원칙
- Apple 내부 소스나 비공개 구현은 다루지 않습니다. **Apple이 공식적으로 설명한 개념**과 **신뢰할 수 있는 2차 자료**만 씁니다.
- 각 항목은 셋으로 나눕니다: **[Apple 공식]** Apple Newsroom, Apple Developer, Apple Machine Learning Research / **[2차 자료]** 언론, 이벤트 발표 요약 / **[추정]** 실제 구현이라고 확인할 수 없는 해석
- 이 문서의 "추정"은 Apple 구현에 대한 주장이 아니라 **결과 이미지 특성을 재현할 때 참고할 가설**입니다.

---

## 1. 기능별 공개 설명

### 1.1 Smart HDR (2018 iPhone XS ~ Smart HDR 5)
- **[Apple 공식]** iPhone XS 보도자료: Smart HDR은 sensor, ISP, Neural Engine 개선을 바탕으로 HDR 사진을 촬영합니다.
- **[2차 자료]** 여러 노출을 합쳐 HDR 한 장을 만들며 셔터 지연이 없다고 설명됩니다.
- **[Apple 공식]** iPhone 12 (Smart HDR 3): machine learning으로 사진의 **white balance, contrast, texture, saturation을 지능적으로 조정**하고, 그림자 속 얼굴의 디테일을 살리며 해당 피사체의 선명도를 높입니다.
- **[Apple 공식]** Smart HDR 4 (iPhone 13): 그룹 사진의 **각 인물마다** contrast, lighting, skin tone을 따로 최적화합니다(개별 person mask). sky segmentation으로 **하늘 영역 노이즈를 제거**합니다.
- **[Apple 공식]** iPhone 15 Pro (next-gen Smart HDR): 피사체와 배경의 **skin tone을 더 실제처럼** 렌더링하고, Photos 앱에서 볼 때 **더 밝은 highlight, 풍부한 midtone, 깊은 shadow**를 보여줍니다(HDR 디스플레이 표시와 관련).

### 1.2 Deep Fusion (2019 iPhone 11 Pro ~)
- **[Apple 공식]** advanced machine learning으로 **pixel-by-pixel 처리**를 해서 사진 전체의 **texture, detail, noise**를 최적화합니다.
- **[2차 자료]** 셔터 전에 짧은 노출 여러 장, 셔터 시 긴 노출 1장을 찍고 Neural Engine이 픽셀 단위로 합칩니다(2019 발표 설명).
- **[2차 자료]** 밝은 곳은 Smart HDR, 중간~저조도는 Deep Fusion, 어두운 곳은 Night mode로 나뉜다고 설명됐습니다.

### 1.3 Night mode (2019 ~)
- **[Apple 공식, 지원 문서]** 저조도에서 자동으로 켜지며 노출 시간은 장면에 따라 달라집니다.
- **[2차 자료, Apple 발표 인용]** **adaptive bracketing**: 움직임과 빛의 양에 따라 짧은 프레임과 긴 프레임을 고릅니다. 프레임을 정렬하고, 흐린 영역은 버리고, 대비와 색을 조정하고, denoise 후 enhancement를 합니다.
- **[Apple 공식]** iPhone 15 Pro: Photonic Engine 기반으로 Night mode의 **디테일이 더 선명하고 색이 더 생생해졌다**고 설명합니다.

### 1.4 Photonic Engine (2022 iPhone 14 ~)
- **[Apple 공식]** hardware와 software의 깊은 통합으로 **중간~저조도 성능**을 높인 "enhanced image pipeline"입니다.
- **[Apple 공식]** **Deep Fusion을 imaging process의 더 이른 단계에 적용**해서 디테일과 섬세한 질감을 보존하고, 색을 개선하고, 사진에 더 많은 정보를 남깁니다.
- **[2차 자료]** 압축되지 않은(raw에 가까운) 이미지에 multi-frame 처리를 적용한다고 해석됩니다.
- **[추정]** "이른 단계"는 비선형 톤 매핑과 양자화 **이전**의 linear/high-bit 데이터에서 합성한다는 뜻으로 볼 수 있습니다. Samsung의 RAW 입력 노드(AI ISP, MPI Night)와 개념상 대응합니다.

### 1.5 Semantic / Scene-aware processing (segmentation)
- **[Apple ML Research]** Camera 앱은 image segmentation(픽셀 단위 이해)에 의존해서 이미지를 develop합니다.
- **[Apple ML Research]** 분할 카테고리: **person, skin, hair, sky, teeth, glasses** (panoptic segmentation, on-device 경량 네트워크).
- **[Apple ML Research]** **sky와 skin segmentation이 denoising과 sharpening 알고리즘을 구동**해서 저텍스처 영역의 품질을 높입니다.
- **[Apple ML Research]** person과 skin segmentation이 최대 4명 그룹 사진의 semantic rendering을 구동해 **각 인물의 contrast, lighting, skin tone**을 최적화합니다.
- **[Apple ML Research]** Photographic Styles는 person, skin, sky mask로 **올바른 영역에만 조정을 적용하면서 skin tone을 보존**합니다.
- **[Apple Developer]** `AVSemanticSegmentationMatte`(skin, hair, teeth, glasses, sky 등), `AVPortraitEffectsMatte`, `AVCapturePhotoOutput.QualityPrioritization`(speed / balanced / quality).

### 1.6 Photographic Styles (iPhone 13 ~, iPhone 16 신세대)
- **[Apple 공식]** 일반 필터와 달리 **촬영 파이프라인 안에서** 적용되며 skin tone 등을 보존하는 선택적 조정입니다.
- **[2차 자료]** iPhone 16: undertone 스타일(Cool Rose, Neutral, Amber, Rose Gold, Gold)과 tone/color 2축 조정을 제공합니다.
- **[추정]** 전역 LUT가 아니라 **segmentation mask 기반의 국소 tone/color 조정**입니다(ML Research 설명과 일치).

### 1.7 HDR 표시 / Gain Map
- **[Apple Developer, WWDC24 "Use HDR for dynamic image experiences in your app"]** headroom, tone mapping, **Adaptive HDR**(SDR 이미지에 gain map 내장, ISO 21496-1 계열) 개념을 설명합니다.
- **[추정]** iPhone 사진의 "밝은 highlight" 인상은 SDR base 렌더링과 HDR gain map 표시를 함께 봐야 합니다. Samsung도 Ultra HDR(JPEG_R) gain map 경로가 있으므로(`SecImageCodecNode.setGainMapSubImage`) **비교할 때 SDR과 HDR 표시를 분리해서 측정해야 합니다.**

---

## 2. 공개 개념으로 본 Apple 파이프라인 (개념도)

```
Sensor (quad-pixel 등)
  → [항상 버퍼링] 셔터 전후 다중 프레임 (짧은 노출 + 긴 노출, adaptive bracketing)      [공식/2차]
  → 정렬·병합 (Smart HDR / Deep Fusion / Night)                                          [공식]
      · Photonic Engine: 병합을 압축 이전, 이른 단계에서 수행                                  [공식]
  → Segmentation (person/skin/hair/sky/teeth/glasses)                                    [공식 ML Research]
  → Semantic rendering
      · 인물별 contrast / lighting / skin tone                                             [공식]
      · sky 영역 denoise, skin/sky 기반 denoise·sharpen 조절                                  [공식]
      · white balance / contrast / texture / saturation ML 조정 (Smart HDR 3)               [공식]
  → Photographic Styles (mask 기반 국소 조정, skin tone 보존)                                [공식]
  → 출력 (HEIF/JPEG + HDR gain map, Photos 앱에서 HDR 표시)                                  [공식 Developer]
```

**구현 순서·연산 세부(어떤 곡선, 어떤 NR 커널, 어떤 CCM)는 공개되지 않았습니다.** 위 순서는 공개 설명을 이어 붙인 개념도일 뿐 실제 내부 구현이 아닙니다.

---

## 3. 결과 이미지 특성 — 공개 설명과 관찰 가설의 구분

| 특성 | Apple 공개 설명 | 재현용 관찰 가설(검증 필요) |
|---|---|---|
| Skin tone | true-to-life skin tones, 인물별 skin tone 최적화 | 피부 hue를 좁은 범위로 안정화, 과채도 억제 [추정] |
| Highlight | brighter highlights (HDR 표시) | SDR에서는 부드러운 roll-off, HDR에서는 gain map으로 밝게 [추정] |
| Midtone / Shadow | richer midtones, deeper shadows | 그림자를 과하게 들어올리지 않고 global contrast 유지 [추정] |
| Texture / Detail | texture, detail 최적화 (Deep Fusion) | 강한 edge halo 대신 중주파 texture 보존 [추정] |
| Noise | sky segmentation 기반 하늘 denoise | 평탄 영역 NR은 강하게, texture 영역은 보존 [추정] |
| Color | ML로 WB/contrast/saturation 조정 | 장면 맥락(조명색) 일부 보존, 따뜻한 쪽 WB 경향 [추정 — 기기·버전마다 다름] |

---

## 중간 결과

### Confirmed (Apple 공식 자료)
- Smart HDR, Deep Fusion, Night mode, Photonic Engine은 모두 multi-frame 기반이며, Photonic Engine은 병합을 더 이른(비압축) 단계로 옮겼습니다.
- Segmentation(person/skin/hair/sky/teeth/glasses)이 denoise, sharpen, 인물별 tone/skin, Photographic Styles를 구동합니다.
- Smart HDR 3는 ML로 WB, contrast, texture, saturation을 조정합니다.

### Likely
- Apple look의 상당 부분은 **segmentation mask 기반 국소 렌더링**에서 나옵니다. 따라서 Samsung 출력에 **전역 LUT 하나만** 적용해서는 재현에 한계가 있습니다.

### Unknown
- 구체적인 tone curve, CCM, NR 강도, sharpen 커널은 비공개입니다.

### Next Targets
- 동일 장면 iPhone/Samsung 페어 촬영으로 위 가설을 **수치로** 검증합니다(`image-tuning-points.md` 8장).

---

## Sources
- [Apple Newsroom — iPhone 14 Pro (Photonic Engine)](https://www.apple.com/newsroom/2022/09/apple-debuts-iphone-14-pro-and-iphone-14-pro-max/)
- [Apple Newsroom — iPhone 15 Pro (next-gen Smart HDR)](https://www.apple.com/newsroom/2023/09/apple-unveils-iphone-15-pro-and-iphone-15-pro-max/)
- [Apple Newsroom (CA) — iPhone XS (Smart HDR)](https://www.apple.com/ca/newsroom/2018/09/iphone-xs-and-iphone-xs-max-bring-the-best-and-biggest-displays-to-iphone)
- [Apple Machine Learning Research — Panoptic Segmentation](https://machinelearning.apple.com/research/panoptic-segmentation)
- [Apple Support — Use Night mode on your iPhone](https://support.apple.com/102519)
- [Apple Developer — AVCapturePhoto](https://developer.apple.com/documentation/avfoundation/avcapturephoto.md)
- [Apple Developer — WWDC24 Use HDR for dynamic image experiences](https://developer.apple.com/videos/play/wwdc2024/10177/)
- [TechCrunch — Deep Fusion beta (2차)](https://techcrunch.com/2019/10/01/apple-launches-deep-fusion-feature-in-beta-on-iphone-11-and-iphone-11-pro/)
- [The Next Web — Deep Fusion (Apple 보도자료 인용, 2차)](https://thenextweb.com/news/iphone-11-apple-deep-fusion-camera)
- [MacRumors — Night Mode guide (2차)](https://www.macrumors.com/guide/night-mode)
- [PetaPixel — iPhone 11 Night mode (2차)](https://petapixel.com/2019/09/10/apple-updates-iphone-xr-to-iphone-11-with-dual-cameras-and-night-mode)
- [Exibart Street — iPhone 12 Smart HDR 3 (Apple 보도자료 인용, 2차)](https://www.exibartstreet.com/news/iphone-12-mini-pro-max/)
- [MacRumors — iPhone 16 Photographic Styles (2차)](https://macrumors.com/guide/iphone-16-photographic-styles)
- [Greg Benz — Apple ISO gain map (2차)](https://gregbenzphotography.com/hdr-photos/apple-macos-ios-hdr-iso-gain-map-21496-1/)
