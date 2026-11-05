# 방금그집

팀 ziafuture · 현대자동차그룹 차량용 앱 개발 공모전 출품작

운전 중 "방금 그거 뭐야?" 한마디로, 몇 초 전에 지나친 가게를 지나온 길에서 거꾸로 찾아 찜해 두는 Pleos Connect 앱입니다. 다시 그 근처를 지나거나 목적지가 근처면 먼저 알려 주고, "응" 한마디로 경유지에 넣습니다.

- 앱 설명·동작·확인 상태: [passed/README.md](passed/README.md)
- 소스: `passed/src/main/java/io/roadmoment/passed/`
- 시연 경로·화면: `passed/demo/`
- 설치 파일(APK): 이 저장소의 Releases

## 빌드

```sh
./gradlew :passed:testDebugUnitTest :passed:assembleDebug
```

APK: `passed/build/outputs/apk/debug/passed-debug.apk`

장소 검색 키 없이 빌드하면 `[데모]` 가상 장소로 동작합니다. 카카오 로컬 API를 쓰려면 루트 `local.properties`에 `kakao.restKey=발급받은_키`를 넣습니다(커밋하지 않음).

## 사용한 Pleos SDK

SpeechToText(온디바이스) · TextToSpeech · FusedLocation · NaviHelper · Vehicle — 어느 API를 어느 기능에 쓰는지는 [passed/README.md](passed/README.md)에 있습니다.
