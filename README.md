# GazeDot — 안드로이드 시선 추정 시제품

전면 카메라에서 눈·홍채 위치를 검출하고 **사용자별 9점 보정**을 거쳐 화면의 추정 시선 위치에 빨간 점을 표시합니다. 홈 화면이나 다른 앱으로 이동한 뒤에도 카메라 포그라운드 서비스와 터치가 통과하는 오버레이가 동작합니다.

## 현재 상태

Android Studio에서 저장소 루트 폴더를 열 수 있습니다. APK는 로컬에서 빌드하며 빌드 산출물은 저장소에 포함하지 않습니다.

첫 실기기 사용에서 좌우 시선 이동이 잘 반영되지 않는 문제가 보고되었습니다. 보정값 저장·재사용, 머리 각도·거리 변화 보완은 아직 구현하지 않았습니다. 현재 버전은 매번 시작할 때 9점 보정이 필요한 실험용 시제품입니다.

## 설치와 사용

1. `app/build/outputs/apk/debug/app-debug.apk`를 안드로이드 기기로 옮겨 설치합니다. Android 8.0/API 26 이상, 전면 카메라가 필요합니다. USB 연결 시 `adb install -r app/build/outputs/apk/debug/app-debug.apk`를 사용할 수 있습니다.
2. GazeDot에서 **권한 허용 및 카메라 시작**을 누릅니다. ‘다른 앱 위에 표시’, 카메라, 알림 권한을 허용합니다. 오버레이 설정 화면에서 앱으로 돌아옵니다.
3. 기기를 고정하고 얼굴을 정면에 둡니다. **눈 검출됨** 상태가 되면 **9점 시선 보정**을 누릅니다.
4. 머리는 움직이지 않고 파란 점을 눈으로 따라갑니다. 점마다 약 2.5초 이상, 유효 표본 22개를 모읍니다. 보정은 약 25~40초가 걸릴 수 있습니다.
5. 보정 후 빨간 점을 확인하고 **홈 화면에서 빨간 점 확인**을 누릅니다. 설정 앱 등 일부 화면에서는 안드로이드 보안 정책에 의해 오버레이가 숨겨질 수 있습니다.
6. 시선으로 버튼을 누르려면 홈 화면의 **1초 시선 클릭 설정**을 누르고 Android 접근성 설정에서 **NoTouch 시선 클릭**을 켭니다. 활성화되면 시선 점이 한 위치에 1초 머물 때 한 번 탭하며, 다른 위치를 바라보면 다시 사용할 수 있습니다.
7. 앱의 **추적 중지** 또는 알림의 **중지**를 누르면 카메라와 오버레이가 종료됩니다.

앱을 처음 실행했을 때 카메라 권한을 거부했다면 다시 허용해야 합니다. 영구 거부한 경우 시스템 앱 설정에서 허용합니다. 화면 꺼짐, 회전, 화면 크기 변경 시 서비스를 종료하며 앱에서 다시 시작·보정합니다. 시스템에 의해 앱 프로세스가 종료된 경우에도 자동으로 카메라를 켜지 않습니다.

## background process인가?

일상적인 의미로는 앱 화면을 떠난 뒤에도 동작하는 백그라운드 기능이지만, Android 구현은 **camera 유형 Foreground Service**입니다. 앱 화면이 보일 때 사용자가 시작하고, 지속 알림을 표시하면서 카메라를 유지합니다. 빨간 점은 `TYPE_APPLICATION_OVERLAY` 창입니다. 시선 탭은 Android 접근성 서비스의 제스처 API를 사용하며, 동작하려면 사용자가 시스템 접근성 설정에서 서비스를 직접 켜야 합니다. 화면 내용은 읽지 않고, 한 위치에 1초 머문 경우 해당 좌표에 짧은 탭 제스처를 보냅니다.

## 실제 구현과 한계

- CameraX ImageAnalysis: 전면 카메라, RGBA 프레임, 센서 회전 보정, 최신 프레임만 처리, 추론 최대 약 15fps.
- MediaPipe Face Landmarker: CPU에서 얼굴·눈·홍채 랜드마크 478개 검출.
- 양쪽 눈의 모서리 축을 기준으로 홍채의 상대 수평/수직 변위를 계산. 눈이 감기거나 랜드마크가 없으면 점을 숨김.
- 9개 목표의 표본 중앙값으로 정규화된 ridge 선형 회귀를 학습해 화면 좌표로 변환.
- 보정 시 시선 변화가 너무 작거나 적합 오차가 크면 실패 처리. 얼굴 위치·거리 변화가 큰 경우 점을 숨김. 미세한 고개 회전까지 완전히 보정하는 모델은 아님.
- 화면 좌표에 작은 움직임 억제 구간과 적응형 지수 평활화를 적용해 고정 시 떨림을 줄이고 큰 움직임에는 빠르게 반응함. 800ms 넘게 결과가 없으면 점을 숨김.
- 전체 화면 오버레이는 터치를 받지 않고 창 불투명도를 0.7로 설정해 Android의 터치 통과 조건을 고려함.
- 시선 위치가 1초 동안 작은 범위 안에 머물면 접근성 제스처로 한 번 탭하며, 시선이 이동하면 다시 활성화됨. 작은 버튼에서는 시선 추정 오차로 인접 항목을 누를 수 있으므로 사용 전 보정과 실제 기기 확인이 필요함.

**홍채 검출 모델 자체가 화면 시선 좌표를 출력하는 것은 아닙니다.** 이 앱은 개인 보정 기반의 초기 시선 추정 실험입니다. 작은 버튼을 정확하게 선택할 수 있다는 보장이 없으며 기기, 조명, 안경, 눈 형태, 머리 위치에 영향을 받습니다. 보정 데이터의 적합 오차는 실제 사용 정확도가 아닙니다. 별도의 목표점 검증이 필요합니다.

카메라를 사용하는 다른 앱과 동시에 전면 카메라를 공유하지 못할 수 있습니다. 제조사의 배터리 관리, 카메라 정책, 분할 화면·폴더블 화면 전환에 따라 실기기 확인이 필요합니다.

## 개인정보·네트워크

영상은 기기 내 메모리에서 처리하고 프레임 처리 후 해제합니다. 영상·얼굴 데이터 파일 저장, 업로드, 서버 API, 로그인, 분석 SDK가 없습니다. 앱 매니페스트에 INTERNET 권한을 선언하지 않습니다. 보정값도 세션 메모리에만 있으며 서비스가 종료되면 제거됩니다.

## 빌드

Android Studio에서 이 폴더를 열고 SDK 35를 설치합니다. JDK 17 또는 21을 사용합니다. `local.properties`의 `sdk.dir`는 각 PC의 SDK 경로를 사용합니다(버전 관리 제외).

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Gradle 8.13 / AGP 8.9.2 / Kotlin 2.1.20 / CameraX 1.4.2 / MediaPipe Tasks Vision 0.10.26을 고정해 사용합니다. 첫 빌드에는 라이브러리 다운로드를 위한 인터넷이 필요하지만, 앱 실행에는 인터넷이 필요하지 않습니다.

## 실기기 확인 항목

- 보정 후 보정에 쓰지 않은 화면 위치를 바라보며 오차·반응 지연을 측정.
- 머리를 고정한 상태에서 눈만 좌우·상하로 이동했을 때 점이 따라오는지 확인.
- 홈/브라우저로 전환하고 2분 동안 점·알림·터치 통과 확인.
- 눈 감기/얼굴 가리기 시 점 숨김, 복귀 시 추적 재개 확인.
- 회전/화면 끄기/중지 버튼 후 카메라 표시와 점이 사라지는지 확인.
- 오버레이/카메라 권한 거부 및 취소, 다른 카메라 앱 실행 시 확인.

## 시선 정확도 평가 기록 (디버그 빌드 전용)

디버그 빌드의 홈 화면 **평가 · 블라인드 측정 / 평가 · 라이브 미리보기**로 실행한 평가만 기록합니다. 일반 시선 추적은 기록하지 않습니다. 영상·이미지·랜드마크·눈 특징값은 저장하지 않고 좌표·시간·유효 여부만 저장하며, 네트워크로 전송하지 않습니다.

세션마다 앱 전용 저장소의 새 폴더에 두 파일을 만듭니다(기존 세션을 덮어쓰지 않음):

- `/data/data/dev.gazedot/files/evaluation/<session_id>/samples.csv`: 추론 시도 1회당 1행(무효 시도 포함). 좌표는 보정·모델과 같은 화면 정규화 좌표 [0,1]이며 값이 없으면 빈 칸입니다. `model_*`는 평활화 전 기존 `GazeModel.predict` 결과(이미 [0,1]로 제한됨), `cursor_*`는 평활화 후 커서 값입니다. `trial_index`와 `index_in_pass`는 0부터 셉니다. `phase`가 `settling`인 행은 정확도 계산에서 제외합니다.
- `metadata.json`: 모드, 종료 상태·사유, 시각, 기기·앱 버전, 화면 기하, 프레임·추론·무효 사유 카운터, 기록/손실 행 수. 평가 시작 시 `final_state: "incomplete"`로 먼저 쓰고 종료 시 한 번 교체합니다. 프로세스가 중단되면 `incomplete` / `in_progress_or_interrupted`로 남습니다. 소스 커밋은 기기에서 기록하지 않으므로 분석 시 직접 입력합니다.

가져오기(디버그 빌드, USB 디버깅). Windows PowerShell 5.1의 `>`는 바이너리를 깨뜨리므로 `cmd /c`로 저장합니다:

```powershell
adb shell run-as dev.gazedot ls files/evaluation
cmd /c "adb exec-out run-as dev.gazedot tar -cf - files/evaluation > evaluation.tar"
tar -xf evaluation.tar
```

Android Studio에서는 **Device Explorer → /data/data/dev.gazedot/files/evaluation**에서 폴더를 저장할 수 있습니다.

### 오프라인 분석 (`tools/gaze_eval`)

Python 3 표준 라이브러리만 사용합니다(설치 불필요, 네트워크 없음). 세션 폴더 하나 또는 여러 세션이 든 폴더를 받아 `samples.csv`·`metadata.json`만 읽고, 원본 세션 파일은 수정하지 않습니다.

```powershell
# 세션 하나
python tools/gaze_eval/analyze_gaze_eval.py files/evaluation/eval-20261023T081530Z-1a2b3c4d --out analysis/run1 --source-label "feature/gaze-accuracy-evaluation @006d2bc + uncommitted Stage 1-3"
# 여러 세션(하위 폴더 자동 탐색)
python tools/gaze_eval/analyze_gaze_eval.py files/evaluation --out analysis/all --source-label "<빌드 설명>"
# 선택: --min-valid-samples 10 --min-valid-coverage 0.5 --include-incomplete
```

출력: `summary.json`, `presentations.csv`(목표 제시 1회당 1행), `report.md`, `plots/*.svg`. 위 경로와 라벨은 예시입니다. 기준값(최소 유효 표본 10개, 유효 비율 50%)은 결과를 본 뒤 조정하지 말고, 바꾸면 비교하는 모든 버전에 같은 값을 적용합니다. 목표점은 지시한 응시 위치이며 실제 시선의 독립 측정값이 아니므로 결과는 "목표 기준 시선 오차"로 보고합니다. 소스 커밋은 기기에서 기록하지 않으므로 `--source-label`로 직접 적습니다.

테스트(합성 데이터만 사용): `python -B -m unittest discover -s tools/gaze_eval -v`

## 파일 안내

- `MainActivity.kt`: 권한, 상태 화면, 9점 보정 UI.
- `TrackingService.kt`: 카메라 포그라운드 서비스, 추론, 생명주기·종료.
- `LandmarkFeatures.kt`: 홍채의 눈 내부 상대좌표 추출.
- `GazeMath.kt`: 보정 회귀, 품질·얼굴 위치 검사.
- `DotOverlay.kt`: 다른 앱 위의 빨간 점.
- `GazeAccessibilityService.kt`: 사용자가 허용한 경우 화면 좌표에 탭 제스처를 전달.
- `Evaluation.kt`: 평가 목표 격자·순서·시간 구간(디버그 전용).
- `EvaluationLog.kt`: 평가 세션의 로컬 CSV·메타데이터 기록.
- `tools/gaze_eval/analyze_gaze_eval.py`: 평가 기록의 오프라인 분석(목표 기준 오차·커버리지·지터, 보고서·SVG).
- `GazeMathTest.kt`: 미관측 좌표 예측, 무효 보정·얼굴 이동·이상값 검사.

## 모델 출처

Google MediaPipe Face Landmarker float16 v1:
https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task

SHA-256: `64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff`

MediaPipe는 Apache-2.0 라이선스이며 배포 모델의 모델 카드와 관련 고지를 함께 확인하세요:
https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker

Android camera foreground service 문서:
https://developer.android.com/develop/background-work/services/fgs/service-types#camera
