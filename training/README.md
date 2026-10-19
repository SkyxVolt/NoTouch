# 개발 단계 시선 학습

폰 수집 → CSV 내보내기 → PC 회귀 학습 → JSON을 폰에 불러오기 순서입니다. 이 폴더의 도구는 개발용이며 출시 사용자가 매번 실행할 필요는 없습니다.

## 수집 요령

- 같은 기기·화면 방향으로 참가자당 여러 세션을 수집합니다. 한 세션 안에서는 머리와 폰을 고정하세요. 다른 위치·거리 조건은 세션을 나누어 모읍니다.
- 목표를 바라본 뒤 해당 목표를 터치합니다. 손만 움직이고 눈은 목표를 계속 봅니다. 데이터 1행은 여러 프레임을 요약한 한 번의 터치입니다.
- 수집은 세션당 60개 목표입니다. 중단된 세션의 이미 저장한 행도 내보내기에 포함됩니다.
- 터치의 실제 좌표는 검사용 `touch_x/y`, 학습 정답은 보고 있던 목표의 중심 `target_x/y`입니다. 눈을 다른 곳에 둔 채 터치했다면 그 세션은 평가/학습에서 빼세요.
- 최소 50개 학습 터치와 별도 세션의 10개 평가 터치가 필요합니다. 이는 실행을 위한 최소값이며 충분한 일반화 성능을 보장하지 않습니다.
- 참가자 코드에는 실명 대신 P01처럼 임의 코드를 사용하세요. `datasets/`는 Git 제외 대상입니다.

## 설치·학습

```powershell
python -m pip install -r training/requirements.txt
python training/train.py datasets/gazedot-training.csv --list
```

목록에 표시되는 UUID 중 평가용 세션을 고릅니다. 나머지는 학습에 사용됩니다.

```powershell
python training/train.py datasets/gazedot-training.csv --test-session 평가용_UUID --output gaze-model.json
```

여러 사람에게 일반화되는지 평가하려면 특정 참가자를 통째로 제외하세요. 예를 들어 개발자 4명이 P01~P04로 모았다면:

```powershell
python training/train.py datasets/gazedot-training.csv --test-participant P04 --output gaze-model.json
```

CSV 경로를 여러 개 지정할 수 있습니다. 중복 내보내기로 같은 `session,target_index`가 다시 나와도 한 번만 반영합니다. 같은 키의 내용이 다르거나 기기·화면 방향이 섞여 있으면 오류로 중단합니다. 평가 ID가 없거나 잘못되면 자동으로 행을 무작위 분할하지 않고 오류를 표시합니다. 같은 사람의 다른 세션 평가는 새로운 사람에 대한 평가가 아닙니다.

`gaze-model.json`을 폰에 옮겨 **학습된 모델 JSON 불러오기**를 누릅니다. `gaze-model.report.json`은 PC에서 볼 평가 결과입니다. 평가 세션은 모델 학습·정규화 통계·유효 자세 범위 계산에 사용하지 않습니다. 모델을 최종 배포할 때도 독립 평가 데이터를 남겨 두세요.

## 평가 결과 읽기

- `coverage`: 폰과 같은 자세 범위 검사에서 예측을 표시할 수 있는 표본 비율. 낮으면 학습에 포함되지 않은 자세/거리/사람일 수 있습니다.
- `all_predictions`: 범위 검사 이전 모든 평가 표본의 오차.
- `accepted_predictions`: 폰에서 표시 가능한 표본만의 오차. coverage와 함께 읽어야 합니다.
- `mean_abs_x_screen_fraction`: 가로 오차를 화면 폭으로 나눈 평균. 0.1이면 화면 폭의 10%입니다.
- `mean_abs_y_screen_fraction`: 세로 오차를 화면 높이로 나눈 평균.
- `mean_euclidean_normalized`, `rmse_normalized`: 두 축을 각각 0~1로 정규화한 좌표계의 거리 오차. 각도, cm, 화면 대각선 비율을 뜻하지 않습니다.

현재 목표는 화면 안쪽에만 있습니다. 화면 밖 스크롤은 외삽이므로 이 보고서의 오차만으로 검증되지 않습니다. 실기기에서 위/아래 주시 성공률과 일반 주시 중 오스크롤 빈도를 따로 기록하세요. 클릭 성공률, 잘못 감지된 두 번 깜빡임, 실제 FPS도 별도로 확인해야 합니다.

## 모델 규격

`schema_version=2`, `kind=quadratic_ridge`. 특징 순서는 `left_x,left_y,right_x,right_y,face_x,face_y,scale,yaw_proxy,pitch_proxy,roll`입니다. 각 눈의 홍채 상대좌표와 얼굴의 기하학적 특징이며 홍채 무늬나 실제 깊이는 아닙니다.

학습 평균과 표준편차(std 최소 0.01)로 정규화한 z에 대해 `[1, z0…z9, z0²…z9²]`의 21개 항으로 x/y를 예측합니다. 교차항은 없습니다. 기본 ridge 계수는 1.0이고 절편에는 패널티를 주지 않습니다. 출력은 클램프하지 않으며 표시할 때만 화면 안으로 제한합니다.

앱은 모델의 기기/화면 구성, 특징 순서, 행렬 크기, 유한 숫자 등을 검사합니다. 학습 시 자세 범위를 벗어나거나 정규화 특징의 절댓값이 12를 넘으면 점을 표시하지 않습니다. 모델을 불러오는 데 성공했다고 정확도 검증까지 통과한 것은 아닙니다.

## 테스트

```powershell
python -m unittest discover -s training -v
```

합성 데이터 테스트는 파일 형식, 평가 분리와 회귀 계산을 확인할 뿐 실제 눈 추정 성능을 입증하지 않습니다. Android의 `app/src/test/resources/synthetic-trained-fixture.json`은 Python→Kotlin 추론 일치 테스트용으로만 사용합니다.
