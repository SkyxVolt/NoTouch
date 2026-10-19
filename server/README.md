# NoTouch 서버

Android → HTTPS `/api/analyze` → OpenAI Responses API. 두 단계 모두 `gpt-5.4`를 사용합니다. OpenAI 키, 프롬프트, 모델 선택은 서버가 관리합니다. 앱은 얼굴 JPEG/특징 또는 시선/UI 후보만 전송합니다.

## 배포

1. Vercel에서 이 저장소를 가져오고 **Root Directory를 `server`**, Framework Preset을 **Other**로 설정합니다. Node.js 22를 사용합니다.
2. Upstash Redis 데이터베이스를 준비합니다. 사용자 영상 저장용이 아니라 서버 인스턴스가 공유하는 호출 횟수 저장용입니다. 기존 Redis를 쓸 경우 `notouch:*` 키 영역을 이 앱 전용으로 사용합니다.
3. 이 폴더에서 `npm run credentials`를 한 번 실행합니다. 이미 생성된 파일이 있으면 덮어쓰지 않습니다.
4. Vercel 프로젝트의 환경변수에 다음 네 값을 직접 입력합니다. 비밀 값은 채팅이나 Git에 넣지 않습니다.

| 환경변수 | 값 |
|---|---|
| `OPENAI_API_KEY` | OpenAI 프로젝트의 API 키. 두 기능에 하나의 키 사용 |
| `NOTOUCH_TOKEN_SHA256` | 생성된 `.token-hash.txt`의 내용 |
| `UPSTASH_REDIS_REST_URL` | Redis 콘솔의 REST URL |
| `UPSTASH_REDIS_REST_TOKEN` | Redis 콘솔의 REST token |

5. 배포합니다. CLI를 쓰면 Vercel 로그인 후 이 폴더에서 `npx vercel --prod`를 실행합니다. 환경변수 변경 후에는 다시 배포해야 합니다.
6. 새 APK의 GPT 설정에서 배포 주소 `https://프로젝트.vercel.app`와 `.device-token.txt`의 **NoTouch 접속 토큰**을 입력합니다. OpenAI 키를 앱에 입력하지 않습니다. Vercel Deployment Protection이 적용된 주소는 앱이 접근할 수 없으므로 앱이 접근 가능한 production 주소를 사용합니다.

두 토큰 파일은 Git에서 제외했습니다. 접속 토큰은 팀 실험용 공용 자격증명입니다. OpenAI 자체 API에는 사용할 수 없지만 유출되면 이 서버의 한도 안에서 사용할 수 있으므로 공유하지 마세요. 취소하려면 새 토큰을 생성해 서버 해시를 교체하고 재배포합니다. 앱에는 토큰을 영구 저장하지 않으며 추적 종료 시 제거합니다. 서버 주소만 저장합니다. 일반 공개 서비스용 계정/기기별 권한 관리는 별도 구현이 필요합니다.

## 호출 제한과 오류

- Redis Lua 스크립트 한 번으로 전체 **분당 12회 / UTC 날짜당 200회**를 원자적으로 검사·예약합니다. 토큰 교체나 앱 재시작, 서버 인스턴스 증가로 초기화되지 않습니다. 두 단계면 2회이며 실패한 OpenAI 호출도 예약 횟수를 소비합니다.
- 입력 JPEG는 base64 2,000,000자, 후보는 40개, 출력은 800토큰 이내로 제한합니다. 이것은 요금액의 정확한 상한이 아닙니다.
- 인증 오류 401, 잘못된 입력 400, 크기 초과 413, 호출 한도 429, OpenAI 오류 502, 서버 환경변수/Redis 장애 503입니다. 설정이 빠지거나 Redis가 실패하면 OpenAI를 호출하지 않습니다.
- 모델, 도구, 프롬프트, 임의의 이미지 URL을 클라이언트가 지정할 수 없습니다. 얼굴 사진/UI 내용을 서버 로그나 DB에 기록하지 않습니다. Redis에는 시간 구간별 카운터만 기록합니다.
- OpenAI 요청은 `store:false`입니다. 제공자의 모든 데이터 보존이 비활성화된다는 뜻은 아닙니다.

## 검증

`npm test`는 모의 OpenAI/Redis를 이용해 인증, 입력 검증, 서버 소유 모델/스키마, 공유 한도 요청, 장애 시 차단, 오류 비공개 처리를 검증합니다. 실제 Redis Lua 실행·Vercel 배포·OpenAI 실호출은 별도 환경 설정 후 확인해야 합니다. 테스트 성공을 시선 추정 정확도 검증으로 해석하지 않습니다.

참고: [Vercel Node.js Functions](https://vercel.com/docs/functions/runtimes/node-js/advanced-node-configuration), [Vercel 환경변수](https://vercel.com/docs/environment-variables), [Upstash REST API](https://upstash.com/docs/redis/features/restapi).

## 현재 배포 상태 (2026-10-07)

서버 주소: https://notouch-server.vercel.app

Vercel production 배포, 서버 인증, 무료 Upstash Redis 연결을 완료했습니다. 자동 유료 전환은 끄고 생성했습니다. 실제 GPT-5.4 화면 후보 분석 요청이 HTTP 200으로 완료됐습니다(약 3.7초, 258토큰). 실제 폰의 시선 정확도 검증은 별도입니다. Vercel 연동은 `KV_REST_API_URL`/`KV_REST_API_TOKEN`을 자동 설정하며 서버가 이 이름도 지원합니다.

앱의 서버 주소에 위 URL을 넣고, 접속 토큰에는 이 폴더의 `.device-token.txt` 내용을 입력합니다. 이 파일은 Git·배포에서 제외됩니다. OpenAI 키를 폰에 입력하지 않습니다.
