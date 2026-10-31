# 잠깐스톱 (가제)

급한 변의가 왔을 때 가장 가까운 화장실을 내비 경유지로 넣어주고, 가는 동안 차량 기능(시트·공조·조명·음성)으로 운전자를 돕는 Pleos Connect 앱입니다.
2026 Pleos Playground 차량용 앱 개발 공모전 출품작입니다. 기획 내용은 [description.md](description.md)에 있습니다.

## 개발 환경

| 항목 | 버전 |
|---|---|
| Android Studio | 최신 버전 (내장 JDK 17 사용) |
| Gradle / AGP | 8.11.1 / 8.9.2 |
| Kotlin / Compose | 2.0.0 / BOM 2024.06.00 |
| minSdk / targetSdk / compileSdk | 28 / 34 / 35 |

사용 중인 Pleos Connect SDK (공모전 허용 SDK 5종):

| SDK | 버전 | 용도 |
|---|---|---|
| Vehicle | 2.3.2 | 주행 상태 조회, 시트 열선·통풍, 공조, 엠비언트 라이트 |
| NaviHelper | 3.0.5 | 화장실 경유지 추가, 남은 시간·거리 |
| TextToSpeech | 3.3.1 | 음성 안내 |
| SpeechToText | 3.3.1 | 음성 명령 ("화장실", "급해") |
| FusedLocation | 1.1.3 | 현재 위치 |

SDK는 `https://nexus-playground.pleos.ai/repository/maven-releases/`에서 받습니다 (`settings.gradle.kts`에 등록됨).

## 빌드

```bash
./gradlew assembleDebug
```

APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다.

## Pleos Connect Emulator 설정

1. Android Studio → Tools → SDK Manager → SDK Update Sites → `+`
   - Name: `Pleos Connect System Image`
   - URL (Windows/Linux): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
2. SDK Platforms → API 34의 **Pleos Connect system image** 설치 → Android Studio 재시작
3. Device Manager → Create device → **Automotive** → Pleos Connect v2.1.0 선택
   - Advanced Settings: Boot = **Cold**, RAM = **4 GB**
4. 에뮬레이터 실행 후 Virtual Console로 기어·브레이크·크루즈 상태를 바꿔가며 테스트

```bash
adb forward tcp:8088 tcp:8088
# 브라우저에서 http://127.0.0.1:8088/ 접속
```

## 참고

- 공모전 접수양식(`contest-application-consent.docx`)은 개인정보가 들어가므로 `.gitignore`로 제외했습니다.
- Pleos 개발 문서: https://document.pleos.ai/
