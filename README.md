# 방금그집

팀 ziafuture · 현대자동차그룹 차량용 앱 개발 공모전 출품작

**운전 중 "방금 그거 뭐야?" 한마디로, 몇 초 전에 지나친 가게를 지나온 길에서 거꾸로 찾아 찜해 두는 Pleos Connect 앱입니다.** 다시 그 근처를 지나거나 목적지가 근처면 먼저 알려 주고, "응" 한마디로 경유지에 넣습니다.

## APK

- 파일: [`apk/ziafuture_방금그집_release.apk`](apk/) (release 빌드, 0.1.0)
- 장소 검색 키 없이 빌드한 파일이라 `[데모]` 가상 장소로 동작합니다. 시연 영상은 실제 카카오 장소 데이터로 촬영했습니다.

## 테스트 환경

- Pleos Connect 에뮬레이터 (Pleos 2.1.0, Android API 34, 2560×1440)
- 앱 요구사항: minSdk 28, targetSdk 34
- 사용 SDK: SpeechToText 3.3.1(온디바이스, ko-KR) · TextToSpeech 3.3.1 · FusedLocation 1.1.3 · NaviHelper 3.0.5 · Vehicle 2.3.2
- 시연 모드는 마이크·경로 파일 없이 동작합니다. 실제 음성으로 하려면 에뮬레이터 마이크 입력을 켭니다.

## 실행 방법

### 가장 빠른 확인: 시연 모드 (경로 파일·마이크 없이)

1. 에뮬레이터에 설치합니다.
   ```sh
   adb install -r "apk/ziafuture_방금그집_release.apk"
   ```
2. 앱을 열고 **켜기** → 안내 화면 다음 마이크·위치 권한 4개를 허용합니다.
3. 화면을 맨 아래로 내려 **시연 모드** 카드에서 **데모 길 달리기 (96초)**를 누릅니다. 앱이 동쪽으로 43 km/h로 달리는 것처럼 동작합니다.
4. 카드에 적힌 순서(10초 왼쪽 카페, 25초 오른쪽 국숫집 …)대로 가게를 지나고 1~3초 뒤 **방금 그 카페 뭐야**를 누릅니다(말 대신 입력, 음성과 같은 경로로 처리됨).
5. "왼쪽 [데모] 달무리 커피, 카페 맞죠?"라고 물으면 **응**을 누르거나 화면의 **맞아요**를 누릅니다. 찜한 곳이 목록에 생깁니다.
6. 입력란에 "방금 오른쪽 빵집 뭐야", "아니 그 앞에 거", "지워" 같은 문장을 직접 넣어 볼 수도 있습니다.

### 실제 음성으로

1. 에뮬레이터 마이크 입력을 켭니다(Extended controls → Microphone → "Virtual microphone uses host audio input").
2. Extended controls → Location → Routes에서 [`passed/demo/demo-route.gpx`](passed/demo/demo-route.gpx)를 불러와 재생합니다(시연 모드의 데모 길과 같은 경로). 앱은 차가 움직일 때만 듣습니다.
3. 가게를 지나고 1~3초 뒤 **"방금 그 카페 뭐야?"**라고 말하고, 질문에 **"응"**이라고 답합니다.
4. 같은 경로를 다시 재생하면 찜한 곳 400m 앞에서 "찜한 ○○, 근처예요. 들를까요?"라고 묻습니다.

동작 단계, 대화 예시, 확인 상태는 [passed/README.md](passed/README.md)에 있습니다.

## 필요한 설정 (소스에서 빌드할 때)

```sh
./gradlew :passed:testDebugUnitTest :passed:assembleDebug
```

- APK: `passed/build/outputs/apk/debug/passed-debug.apk`
- 실제 장소를 쓰려면 루트 `local.properties`에 `kakao.restKey=발급받은_카카오_REST_키`를 넣습니다. 키가 없으면 데모 장소로 동작합니다. 키는 커밋하지 않습니다.
- 디버그 빌드에는 주행 화면 미리보기와 상태 표시가 더 있습니다.
- Pleos SDK는 `https://nexus-playground.pleos.ai/repository/maven-releases/`에서 받습니다(`settings.gradle`에 설정됨).
