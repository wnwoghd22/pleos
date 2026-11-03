# 뿌지직 (pzzk)

**똥줄 탈 때, 차가 사람을 위해 할 수 있는 모든 것.**

급한 변의가 왔을 때 가장 빨리 닿는 화장실을 내비 경유지로 넣고, 가는 동안 차량 기능(시트·공조·조명·음성)으로 운전자가 버티도록 돕는 Pleos Connect 앱입니다.
2026 현대자동차그룹 차량용 앱 개발 공모전 출품작입니다. 기획 내용은 [description.md](description.md)에 있습니다.

## APK

- 위치: [`apk/JaehongJu_pzzk_release.apk`](apk/JaehongJu_pzzk_release.apk) (서명된 release 빌드)
- 패키지 이름: `com.jamkkanstop.app` (개발 중 가제를 쓰던 때의 이름을 그대로 둠)

## 실행 방법

1. Pleos Connect v2.1.0 에뮬레이터를 켭니다 (설정은 아래 [Pleos Connect Emulator 설정](#pleos-connect-emulator-설정)).
2. **내비 앱을 먼저 실행하고 1~2분 기다립니다.** 부팅 직후에는 내비가 앱의 요청에 답하지 않아, 도착 시간을 거리로만 추정하고 경로도 넣지 못합니다.
   ```bash
   adb shell monkey -p ai.umos.maps.android.navigation.app -c android.intent.category.LAUNCHER 1
   ```
3. APK를 설치하고 앱 목록에서 **뿌지직**을 엽니다.
   ```bash
   adb install apk/JaehongJu_pzzk_release.apk
   ```
4. 처음 열 때 위치 권한(화면에는 "알 수 없는 작업"으로 표시됨)을 **허용**합니다.
5. **화장실 급해요** 버튼을 누릅니다. 가장 빨리 닿는 화장실과 예비 후보가 나오고, 내비 경로에 들어간 뒤 음성으로 안내합니다.
6. 안내가 끝나면 앱이 8초 동안 듣습니다. "배 아파"(따뜻하게), "식은땀"(시원하게), "다른 데"(예비 후보로) 같은 말에 반응합니다.

## 테스트 환경과 필요한 설정

| 항목 | 내용 |
|---|---|
| 에뮬레이터 | Pleos Connect v2.1.0 (Android 14, API 34, x86_64), RAM 4GB, Cold boot |
| 위치 바꾸기 | `adb emu geo fix <경도> <위도>` (예: 판교 `adb emu geo fix 127.1046 37.3948`) |
| 도착 확인 | 안내 중 위치를 화장실 좌표로 옮기면 10초 안에 "도착했어요" 화면과 음성이 나옵니다 |
| 음성 입력 | Extended controls → Microphone → "Virtual microphone uses host audio input"을 켜고 PC 마이크로 말합니다 |

에뮬레이터에서 확인할 수 없는 것:

- **차량 값 변경.** 시트 열선·통풍, 공조, 조명 요청은 받지만 값이 바뀌지 않습니다(시스템 공조 바로 직접 바꿔도 마찬가지). 실차나 Virtual Console에서 확인해야 합니다.
- **내비 현재 위치.** 내비 앱이 에뮬레이터 GPS를 받지 않아 성남 수정구 시흥동에 머뭅니다. 그래서 내비가 계산하는 도착 시간은 그 위치를 기준으로 합니다. 앱의 후보 선정은 `geo fix` 위치를 따릅니다.

## 개발 환경

| 항목 | 버전 |
|---|---|
| JDK | 21 (Gradle 8.11.1은 Java 25에서 실행되지 않음) |
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

"화장실 급해요" 버튼 → 현재 위치(FusedLocation, 미지원 시 내비 위치) → 가까운 화장실 5곳 + 가까운 휴게소·졸음쉼터 2곳 추림 → 내비 `requestTrip`으로 실제 도착 예정 시간 조회(내비가 답하지 않으면 거리로 추정) → 가장 빨리 닿는 곳 + 예비 1곳 선택 → 내비 경로에 추가 → 음성 안내

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

`app/src/main/assets/toilets.json`에 전국 화장실 **23,725곳**이 들어 있습니다 (약 4MB, 앱 시작 때 미리 읽음).

| 종류 | 수 | 출처 |
|---|---|---|
| 공중화장실 | 20,382 | [전국공중화장실표준데이터](https://www.data.go.kr/data/15012892/standard.do) (좌표 있음) |
| 주유소·충전소 | 2,760 | 위 데이터에 개방화장실로 등록된 곳 (이름으로 분류) |
| 고속도로 휴게소 | 203 | 한국도로공사 휴게소 위치정보 API ([data.ex.co.kr](https://data.ex.co.kr)) |
| 졸음쉼터(화장실 있음) | 253 | [전국졸음쉼터표준데이터](https://www.data.go.kr/data/15028203/standard.do) |
| 그 밖의 휴게소 | 127 | 공중화장실 데이터 중 이름에 "휴게소"가 든 곳 |

공중화장실은 행정안전부 "공중화장실정보"(좌표 없음, 더 최신)로 개방시간을 보강하고, "미개방"인 곳은 뺍니다.
휴게소·졸음쉼터·주유소는 차를 세우기 쉬운 곳으로 보고, 도착 시간이 1분 안쪽으로 비슷하면 앞에 둡니다.
고속도로에서는 직선으로 가까운 곳이 대개 도로 옆 마을이라 출구로 나가야 합니다. 그래서 가까운 휴게소·졸음쉼터 2곳은 항상 후보에 넣고, 실제 시간은 내비로 비교합니다.

다시 만들기:

```bash
# 1. 공중화장실 표준데이터 CSV는 위 링크에서 받아 data/전국공중화장실표준데이터.csv로 둔다
# 2. 고속도로 휴게소·졸음쉼터 받기 (휴게소 API는 공개 시험용 키 test 사용, --ex-key로 바꿀 수 있음)
python tools/fetch_highway.py
# 3. 합치기
python tools/convert_toilets.py data/전국공중화장실표준데이터.csv     data/고속도로휴게소.csv@REST_AREA data/졸음쉼터.csv@REST_AREA     --hours-from data/공중화장실정보.csv
```

- 좌표가 없는 행과 이동화장실(행사용 임시 화장실)은 제외됩니다.
- 원본 CSV는 크기가 커서 저장소에 넣지 않습니다(`data/`는 `.gitignore`).

## 음성 명령

시작은 "화장실 급해요" 버튼으로 합니다. 그다음부터는 손을 쓰지 않아도 됩니다.
안내 음성이 끝나면 앱이 한 번(최대 8초) 듣고, 알아들은 말에 따라 움직입니다.

| 이렇게 말하면 | 하는 일 |
|---|---|
| "배 아파", "따뜻하게", "추워" | 따뜻하게 모드 |
| "식은땀", "시원하게", "더워", "메스꺼워" | 시원하게 모드 |
| "다른 데", "예비", "거기 말고" | 예비 후보로 바꾸기 |
| "원래대로" / "취소" / "괜찮아" | 버티게 돕기 끄기 / 안내 닫기 / 아무것도 안 함 |

- 못 알아들으면 다시 묻지 않고 조용히 넘어갑니다. 재촉하지 않으려는 것입니다.
- "말만 하면 시작"은 만들 수 없습니다. Pleos의 호출어·상시 듣기 API(`AmbientAi`)는 시스템 앱만 쓸 수 있고, 일반 앱이 부르면 SDK가 거부합니다.
- 경로를 넣으면 내비가 화면을 가져가고 앱은 백그라운드로 갑니다. 그러면 시스템이 앱을 얼려서, 안내 중에는 포그라운드 서비스("화장실로 안내 중" 알림)로 앱을 유지합니다.
- 에뮬레이터에서 직접 말해보려면 Extended controls → Microphone에서 "Virtual microphone uses host audio input"을 켜고 PC 마이크로 말하면 됩니다.

## 도착하면

다음 둘 중 먼저 오는 것으로 도착을 판단합니다.

- 내비의 도착 이벤트(`onDestinationArrived` / `onWayPointArrived`). 장소 이름이 같으면 도착입니다.
- 위치가 화장실 100m 안. 내비 앱 구현에 따라 이벤트가 오지 않을 수 있어서 함께 봅니다. 10초마다 확인합니다.
  처음부터 화장실 200m 안에서 버튼을 눌렀다면 위치로는 판단하지 않습니다.

도착하면 바꾼 시트·공조·조명을 되돌리고, 음성으로 "도착했어요. 천천히 다녀오세요."라고 안내한 뒤 안내용 포그라운드 서비스를 끝냅니다.
경유지로 넣었다면 내비가 원래 목적지로 이어서 안내합니다.

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
./gradlew testDebugUnitTest   # 단위 테스트 (순위 결정, 개방시간 해석, 음성 명령, 도착 판단 등)
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

release 서명 키는 저장소에 넣지 않습니다. `local.properties`에 아래 값을 넣으면 서명된 release APK가 만들어지고, 값이 없으면 서명 없이 빌드됩니다.

```properties
release.storeFile=/path/to/key.jks
release.storePassword=...
release.keyAlias=...
release.keyPassword=...
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

- 공모전 접수양식(`contest-application-consent.docx`)과 서명 키 설정(`local.properties`)은 `.gitignore`로 제외했습니다.
- Pleos 개발 문서: https://document.pleos.ai/
