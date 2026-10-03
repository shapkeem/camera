# Tune Camera

Open Camera v1.56.2 (GPL v3, https://opencamera.org.uk/) 기반 개인용 카메라 앱.
원본 소스는 커밋 `a7fa404`, `2fb9e52`, `9edf8f7`(Import 1/3~3/3)에 수정 없이 들어 있고, 이후 커밋이 변경 사항이다.

## 원본 대비 변경
- applicationId: `com.shapkeem.camera` (원본 Open Camera, 기본 카메라와 따로 설치됨)
- 앱 이름: Tune Camera
- 고정 debug 서명 키 `debug.keystore` (로컬/CI 빌드끼리 덮어쓰기 설치 가능). 개인 테스트용 키라서 일부러 커밋함
- Java 패키지(`net.sourceforge.opencamera`)는 업스트림 병합을 쉽게 하려고 그대로 둠

## 빌드
```
cd app
echo "sdk.dir=<Android SDK 경로>" > local.properties
./gradlew assembleDebug
# 결과: app/build/outputs/apk/debug/app-debug.apk
```
GitHub에 push하면 Actions(`Build APK`)가 APK를 만들어 artifact `TuneCamera-debug-apk`로 올린다.

## 라이선스
GPL v3 (`gpl-3.0.txt`). 배포할 경우 소스 공개 의무가 있다.
