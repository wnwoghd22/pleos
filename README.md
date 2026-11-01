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

## 핵심 흐름

"화장실 급해요" 버튼 → 현재 위치(FusedLocation, 미지원 시 내비 위치) → 가까운 화장실 5곳 추림 → 상위 3곳은 내비 `requestTrip`으로 실제 도착 예정 시간 조회 → 가장 빨리 닿는 곳 + 예비 1곳 선택 → 내비 경로에 추가 → 음성 안내

경로에 넣는 방식:

| 상황 | 동작 |
|---|---|
| 안내 중이 아님 | 화장실을 목적지로 설정 |
| 안내 중, 경유지 없음 | 첫 번째 경유지로 추가 (원래 목적지 유지) |
| 안내 중, 경유지 있음 | 화장실을 맨 앞에 두고 경로 재요청 (경유지는 최대 2개라 기존 두 번째 경유지는 빠짐) |

```
app/src/main/java/com/jamkkanstop/app/
├── MainActivity.kt            화면 (주행 중 1~2초 안에 읽히는 큰 글자)
├── data/                      화장실 DB, 개방시간 해석, 거리 계산
├── urgent/                    후보 순위 결정(UrgentStopPlanner), 화면 상태(ViewModel)
└── pleos/                     Pleos SDK 래퍼: 내비, 위치, 음성, 차량(시트·공조·조명)
```

## 화장실 데이터

`app/src/main/assets/toilets.json`에 전국 공중화장실 **23,308곳**이 들어 있습니다 (약 4MB, 앱 시작 때 미리 읽음).

만드는 방법:

1. 좌표가 있는 [전국공중화장실표준데이터](https://www.data.go.kr/data/15012892/standard.do) CSV를 받아 `data/`에 둡니다.
2. 좌표는 없지만 더 최신인 행정안전부 "공중화장실정보" CSV가 있으면 `--hours-from`으로 함께 넣습니다.
   주소가 같은 곳은 그 파일의 개방시간으로 바꾸고, "미개방"인 곳은 뺍니다.

```bash
python tools/convert_toilets.py data/전국공중화장실표준데이터.csv --hours-from 공중화장실정보.csv
# 휴게소·주유소 데이터는 종류를 지정해서 함께 넣기
python tools/convert_toilets.py data/전국공중화장실표준데이터.csv 휴게소.csv --type REST_AREA
```

- 좌표가 없는 행과 이동화장실(행사용 임시 화장실)은 제외됩니다.
- 원본 CSV는 크기가 커서 저장소에 넣지 않습니다(`.gitignore`).

## 가는 동안 버티게 돕기

안내가 시작되면 두 가지 모드를 고를 수 있습니다. 차가 지원하는 기능만 바꾸고(`check*Capabilities`), "원래대로"·"닫기"를 누르면 되돌립니다.

| 모드 | 하는 일 |
|---|---|
| 배 아파요 · 따뜻하게 | 운전석 시트 열선(중간 단계), 핸들 열선, 켜진 통풍은 끔 |
| 식은땀 나요 · 시원하게 | 통풍 시트, 설정 온도 2℃ 낮춤, 바람 한 단계 세게·얼굴 쪽, 외기 유입 |
| 공통 | 엠비언트 라이트를 차분한 하늘색·낮은 밝기로, 음성 호흡 안내 |

공조 전원이 꺼져 있으면 켜고, 되돌릴 때 다시 끕니다.
Pleos Connect 2.1.0 에뮬레이터는 차량 설정 요청을 받기만 하고 값은 바꾸지 않습니다. 시스템 공조 바에서 직접 켜도 마찬가지입니다. 그래서 실제 반영 여부는 실차나 Virtual Console에서 확인해야 합니다.

## 빌드

```bash
./gradlew assembleDebug
```

APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다.

```bash
./gradlew testDebugUnitTest   # 순위 결정·개방시간 해석 단위 테스트
```

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
