# 검증 기록 — 2026-09-16

- `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug`: BUILD SUCCESSFUL.
- JVM 단위 테스트: 3개, 실패 0개. 보정 후 미관측 좌표 예측, 무효 보정 거부, 위치 변화 거부, 중앙값 이상값 처리를 확인.
- Android lint: 오류 0개, 경고 8개. 경고는 의존성 새 버전, 백업 설정, Kotlin 편의 API, 문자열 지역화 안내.
- `apksigner verify --verbose`: APK v2 서명 검증 통과.
- 최종 APK 권한 확인: CAMERA, SYSTEM_ALERT_WINDOW, FOREGROUND_SERVICE, FOREGROUND_SERVICE_CAMERA, POST_NOTIFICATIONS, ACCESS_NETWORK_STATE 및 앱 내부 수신기 권한. INTERNET 권한 없음.
- APK: `app/build/outputs/apk/debug/app-debug.apk` (23,184,338 bytes).
- `adb devices -l`: 연결된 기기 없음. **실기기 설치, 카메라 추론, 다른 앱 위 표시, 실제 시선 정확도는 아직 검증하지 않음.** README의 실기기 확인 항목으로 후속 테스트 필요.

Windows의 긴 임시 경로 때문에 Gradle JVM의 Unix-domain loopback 생성이 실패하면, 빌드 세션의 `JAVA_TOOL_OPTIONS`에 `-Djdk.net.unixdomain.tmpdir=<짧은 기존 임시 폴더 경로>`를 설정할 수 있다. 검증 당시 이 방법을 사용했으며 시스템 전역 설정은 변경하지 않았다.
