# 폰 부착 IR 카메라와 머리 보정 검토

조사일: 2026-10-07. 공식 제조사·프로젝트 자료 기준이며 실제 구매·S23 호환성 시험은 수행하지 않았습니다. 아래는 구매 후보와 설계안이며 외장 카메라 기능을 구현했다는 의미가 아닙니다.

## 결론

홍채/동공의 모양과 위치에 머리의 회전·이동을 결합하는 방향은 타당합니다. 하지만 홍채 무늬 등록 또는 일반 LLM 한 번의 추론만으로 정확한 화면 좌표가 결정되지는 않습니다. 우선 근거리에서 선명한 눈 원본 영상을 확보하고, 장착 위치가 고정된 카메라-화면 관계를 보정해야 합니다.

실시간 눈 영상을 폰에 보내는 용도로 검증 가능한 저가 Bluetooth NIR 완제품은 이번 조사에서 확인하지 못했습니다. Bluetooth가 달린 보드와 영상 전송 가능한 카메라는 다른 개념입니다. 무선 영상은 Wi-Fi, 소형 모듈 직결은 USB UVC를 우선 검토합니다. Bluetooth는 카메라 쪽에서 계산한 작은 시선/머리 좌표 패킷을 보내는 용도로 검토할 수 있습니다.

## 후보 제품

| 후보 | 확인한 특성 | 연결/가격 | 판단 |
|---|---|---|---|
| Raspberry Pi Camera Module 3 **NoIR Standard** + Zero 2 W | 12MP, autofocus, IR 필터 없는 선택지. Zero 2 W는 CSI-2, Wi-Fi, Bluetooth 4.2/BLE 제공 | 카메라→CSI→Pi→Wi-Fi→폰. 공식 Camera 3 제품군 $25부터, Zero 2 W 별도. 배터리·카드·Zero용 케이블·조명·거치대 추가 | 무선 영상 프로토타입 우선 후보. NoIR 표준 렌즈의 정확한 국내 판매가/재고 확인 필요. 자동초점은 실험 중 고정 가능한지 검증 |
| Arducam OV9281 **B0165** M12 NoIR | 1MP 흑백 글로벌 셔터, 수동 초점·교체형 M12 렌즈, 공식 가이드에 IR-cut 없음 | CSI→호환 Pi 보드→Wi-Fi/분석 후 BLE. 별도 드라이버/OS 호환 확인 | 눈 촬영 거리·렌즈를 조절하는 실험 후보. 구매 시 SKU와 IR 필터 구성을 확인 |
| Arducam OV9281 **B0332** USB UVC | 1280×800, 흑백 글로벌 셔터, IR-cut 없음, USB 2.0, 제조사 표기 MJPEG 최대 100fps | USB-C OTG→폰. 공식 페이지 $49.99, 배송/세금 별도 | **기본 사양 초점 1m~무한대라 30~50cm 사용에 즉시 추천하지 않음.** 렌즈 교체도 제조사 문의 대상. 근거리 초점 확인 전 구매 보류 |

NoIR는 열화상 카메라가 아닙니다. 센서만 사면 눈을 비추는 NIR 조명이 자동으로 포함되는 것도 아닙니다. 눈을 향하는 조명은 사용 거리·노출 시간을 포함한 안구 안전성이 확인된 설계/모듈을 사용합니다. 일반 야간 감시용 고출력 IR 조명을 그대로 눈 앞에 붙이지 않습니다.

제조사가 Android UVC 호환을 표기해도 S23의 Camera2에서 바로 보인다는 보장은 없습니다. USB host 권한, UVC 앱/라이브러리, USB 대역폭·전력과 전면 카메라 동시 사용을 실제로 확인해야 합니다. 현재 앱은 CameraX 전면 카메라 입력만 구현되어 있습니다.

## Bluetooth로 연결하려면

1. NIR 카메라를 Pi 같은 처리 보드에 연결합니다. 카메라 모듈 자체는 Bluetooth 주변기기가 아닙니다.
2. 보드에서 동공/홍채와 머리 자세를 계산합니다. Zero 2 W가 목표 모델/FPS를 감당하는지는 벤치마크가 필요하며 단순 스트리밍부터 시작합니다.
3. BLE GATT 서비스를 만들어 타임스탬프·순번·좌표계 버전·시선 좌표·머리 자세·품질을 알림 패킷으로 보냅니다. Android는 GATT client로 받습니다. 이것은 추가 개발할 프로토콜이며 현재 구현되지 않았습니다.
4. 원본 눈 영상이 필요한 GPT 실험은 Wi-Fi 또는 USB로 전송합니다. BLE는 페어링/설정이나 좌표 전달에 사용합니다.

대역폭 예: 640×480 흑백 8bit 영상 30fps는 압축 전 약 **73.7Mbps**입니다. Bluetooth SIG의 LE 2M 설명은 최대 애플리케이션 데이터율을 약 **1.4Mbps**로 제시합니다. 압축/저해상도 영상이 원리상 불가능하다는 뜻은 아니지만, 홍채 디테일과 지연이 중요한 이 프로젝트에서 기본 영상 경로로 채택할 이유는 약합니다.

## 홍채 각도 + 머리 위치 보정 설계

권장 구현 순서:

1. 실제 사용 거리(예: 30/40/50cm)에서 눈 원본 영상 수집: 초점, 눈 ROI 픽셀 수, 반사, 안경 유무를 비교합니다.
2. 카메라 내부 보정: 초점거리/주점/왜곡을 추정하고, 카메라를 폰에 고정한 상태에서 화면 평면과의 외부 관계를 측정합니다.
3. 눈 ROI에서 동공·홍채의 타원 경계와 가림 정도 추정. 타원만으로 방향을 정할 때 생기는 모호성·각막 굴절·눈꺼풀 가림을 고려합니다. 홍채 무늬 정합은 보조 연구로 분리합니다.
4. 얼굴 랜드마크 기반 머리 pose 추정. 단안의 절대 거리/크기에는 개인 얼굴 크기 가정이 들어가므로 cm 단위 실측으로 취급하지 않습니다. 두 카메라를 쓰면 시간 동기화와 카메라 간 외부 보정도 필요합니다.
5. 눈 방향을 머리 좌표계에서 카메라/화면 좌표계로 변환하고 화면 평면과 교차시킵니다. 머리 이동에 따라 눈의 위치도 바뀌므로 회전각만 보정해서는 부족합니다.
6. 개인 광학축-시각축 차이와 장착/모델 오차를 보정합니다. 개발 학습 데이터에 여러 머리 각도·거리·참가자·조명을 포함하고, 다른 참가자/세션으로 검증합니다. 무보정은 결과로 입증할 목표입니다.
7. GPT-5.4 실험 결과를 동일 데이터에서 비교합니다. LLM이 반환한 각도는 기하학적 측정/정답이 아니며, 신뢰도 출력도 보정된 확률이 아닙니다. 영상 자체에 없는 미세 패턴을 복원했다고 간주하지 않습니다.

## 근거

- [Raspberry Pi Camera Module 3](https://www.raspberrypi.com/products/camera-module-3/)
- [Raspberry Pi Zero 2 W](https://www.raspberrypi.com/products/raspberry-pi-zero-2-w/)
- [Arducam 글로벌 셔터 SKU/필터/렌즈 가이드](https://docs.arducam.com/Raspberry-Pi-Camera/Global-Shutter-Camera/quick-start/)
- [Arducam B0332 제품·가격·초점 사양](https://www.arducam.com/arducam-120fps-global-shutter-usb-camera-board-1mp-720p-ov9281-uvc-webcam-module-with-low-distortion-m12-lens-without-microphones-for-computer-laptop-android-device-and-raspberry-pi.html)
- [Bluetooth SIG LE primer](https://www.bluetooth.com/bluetooth-le-primer/)
- [Android 외장 USB 카메라](https://source.android.com/docs/core/camera/external-usb-cameras)
- [Eye Gaze Tracking Using an RGBD Camera — 홍채/머리 자세 결합 연구](https://www.microsoft.com/en-us/research/wp-content/uploads/2016/02/KinectBasedEyeTracking.pdf)
- [EllSeg — 눈 구성요소 타원 분할 연구](https://arxiv.org/abs/2007.09600)
- [GPT-5.4 모델](https://developers.openai.com/api/docs/models/gpt-5.4)
