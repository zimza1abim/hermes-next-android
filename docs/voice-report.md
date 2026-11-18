# 음성 대화 구현 보고 — Android (2026-11-18)

기획서 `hermes-voice-plan.md`의 A~D 단계를 Android에 구현했다. E(iOS)는 착수하지 않았다(기획서 규칙: Android이 끝나기 전에 iOS를 벌리지 않는다).

## 표 1. 인수 기준 판정

| AC | 구현 파일 | 검증 방법 | 실제 결과 |
|---|---|---|---|
| AC-01 설정 메시지 | `voice/LiveProtocol.kt` | `LiveProtocolTest > setup carries model voice language both transcripts and the instruction` | 통과 |
| AC-02 음성 조각 → PCM | `voice/LiveProtocol.kt` | `LiveProtocolTest > audio parts become pcm bytes at the output sample rate` | 통과 |
| AC-03 입력·출력 자막 | `voice/LiveProtocol.kt` | `LiveProtocolTest > input and output transcription are read separately` | 통과 |
| AC-04 함수 호출 읽기 | `voice/LiveProtocol.kt` | `LiveProtocolTest > a function call carries id name and the verbatim request` | 통과 |
| AC-05 끼어들기 읽기 | `voice/LiveProtocol.kt` | `LiveProtocolTest > interrupted and turnComplete are both read from one frame` | 통과 |
| AC-06 종료 예고 읽기 | `voice/LiveProtocol.kt` | `LiveProtocolTest > goAway timeLeft is read as seconds` | 통과 |
| AC-07 재개 핸들 | `voice/LiveProtocol.kt` + `LiveMachineTest > only a resumable handle is kept and the reconnect uses it` | 단위 테스트 | 통과 |
| AC-08 이상한 메시지 무시 | `voice/LiveProtocol.kt` | `LiveProtocolTest > unknown and broken frames are ignored without throwing` (12종) | 통과 |
| AC-09 끼어들면 재생 큐 비움 | `voice/LiveMachine.kt` | `LiveMachineTest > caller audio fills the queue and interruption empties it at once` | 통과 |
| AC-10 넘기기 중 끼어들어도 결과 유지 | `voice/LiveMachine.kt` | `LiveMachineTest > interrupting while Hermes works keeps the answer` | 통과 |
| AC-11 90초 초과 = 실패 결과 | `voice/LiveMachine.kt` | `LiveMachineTest > 90 seconds is the line between waiting and failing` | 통과 |
| AC-12 종료 예고 → 재연결 | `voice/LiveMachine.kt` | `LiveMachineTest > only a resumable handle is kept and the reconnect uses it` | 통과 |
| AC-13 Ended 정리 1회·5자원 | `voice/LiveMachine.kt` + `voice/LiveController.kt#cleanup` | `LiveMachineTest > hang-up tears down once and names every resource` | 통과 |
| AC-14 끝난 통화의 늦은 메시지 무시 | `voice/LiveMachine.kt` | `LiveMachineTest > messages that arrive after the call ended change nothing` | 통과 |
| AC-15 항상 붙는 규칙 4개 | `voice/Persona.kt` | `PersonaTest > every preset and strength keeps all four rules` (4×4 조합) | 통과 |
| AC-16 조립 순서 고정 | `voice/Persona.kt` | `PersonaTest > the assembly order never changes` | 통과 |
| AC-17 봇별 덮어쓰기 | `voice/Persona.kt` + `VoiceSettingsStore.kt` | `PersonaTest > a bot with an override uses its own voice and tone`, `> instructionsFor applies the override of the bot on the call` | 통과 |
| AC-18 다듬기(마크다운·표·링크·코드) | `voice/HermesHandOff.kt#polish` | `VoiceHandOffTest > markdown tables links and code never reach the speaker` | 통과 |
| AC-19 길이 자르기·빈 답 | `voice/HermesHandOff.kt` | `VoiceHandOffTest > a long answer is cut at a sentence inside the window`, `> an empty answer becomes one honest sentence` | 통과 |
| AC-20 voice-live 표시·턴 짝 | `voice/HermesHandOff.kt` + `AssistantProtocol.kt#submit` | `VoiceHandOffTest > the question carries the voice-live surface and pairs with its turn`, `> the gateway payload marks the surface and the submission id` | 통과 |
| AC-21 실패를 사실대로 | `voice/HermesHandOff.kt` | `VoiceHandOffTest > failure speech never claims success`, `> a failed tool result is marked as an error on the wire` | 통과 |
| AC-22 말 끝 → 첫 음성 1.5초 | `voice/LiveProtocol.kt`(VAD 500ms) + `LiveAudio.kt` | 기기 시험 | **미확인** — 폰에서 사람이 말해야 측정됨. 서버 측 실측은 1,042 ms(스파이크 3) |
| AC-23 스피커 클 때 자기 목소리 | `voice/LiveAudio.kt`(AEC/NS/AGC) | 기기 시험 | **미확인** |
| AC-24 끼어들기 0.3초 감지 | `voice/LiveMachine.kt` + `LiveAudio.kt#stopPlayback` | 기기 시험 | **미확인** — 코드 경로는 테스트로 증명, 실측 시간은 미측정 |
| AC-25 "내 서버 디스크" → Hermes | `voice/LiveController.kt#askHermes` + `AssistantController#askVoice` | 기기 시험 | **미확인** |
| AC-26 30초 넘는 작업 | `voice/LiveMachine.kt`(비동기) + `LiveController` | 기기 시험 | **미확인** — 비동기 선언은 스파이크 1에서 확인 |
| AC-27 승인 카드 | `voice/VoiceScreen.kt#ApprovalCard` + `VoiceActivity` | 기기 시험 | **미확인** |
| AC-28 목소리·말투 변경 | `voice/VoiceSettingsScreen.kt`, `VoiceSettingsStore.kt` | 기기 시험 | **미확인** |
| AC-29 봇별 목소리 | `voice/VoiceSettings.kt#voiceFor` | 단위 테스트 + 기기 | 단위 통과 / 기기 **미확인** |
| AC-30 화면 꺼짐 유지 | `voice/VoiceService.kt`(microphone FGS) | 기기 시험 | **미확인** |
| AC-31 전화 수신 | `voice/LiveAudio.kt#requestFocus` + `LiveController#audioFocusLost` | 기기 시험 | **미확인** |
| AC-32 와이파이→LTE | `voice/LiveMachine.kt`(재연결) + `LiveSocket` | 기기 시험 | **미확인** |
| AC-33 블루투스 경로 | `voice/LiveAudio.kt#route` | 기기 시험 | **미확인** |
| AC-34 15분 통화 | `voice/LiveMachine.kt` + `VoiceService` | 기기 시험 | **미확인** — 120초 유휴에서 goAway 미관측(스파이크 2) |
| AC-35 키 없음·틀린 키·429 | `voice/GeminiKey.kt`, `LiveController#onSocketClosed`, `VoiceActivity#onCreate` | 기기 시험 | **미확인** |
| AC-36 대화창 보기/복귀 | `VoiceActivity`(뒤로 가면 통화 유지, 알림으로 복귀) | 기기 시험 | **미확인** — "통화 중" 띠는 미구현 |
| AC-37 잠금화면·상태바 | `voice/VoiceService.kt`(통화형 알림: 시간·음소거·끊기) | 기기 시험 | **미확인** |
| AC-38 귀에 대기/떼기 | — | — | **미구현·미확인** (근접 센서 화면 끄기는 기획서 UI 항목, A~D 단계에 없음) |
| AC-39 10번 열고 닫기 | `LiveController#cleanup`(단일 정리) | 기기 시험 | **미확인** |

단위 테스트 합계: **515개 중 54개가 음성 기능**, 전체 실패 0. (`app/build/test-results/testDebugUnitTest/*.xml` 집계)

## 독립 검증(다른 모델 계열)이 내 판정과 다르게 본 것

검증자(grok-4.7)는 같은 코드·같은 테스트로 39개를 다시 판정했고, **8개(AC-02·03·08·14·16·18·19·20)를 나는 통과, 검증자는 미확인**으로 봤다. 지적이 맞아서 테스트를 보강하고 코드를 고쳤다.

| AC | 검증자 지적 | 처리 |
|---|---|---|
| AC-02 | 24kHz를 프레임에서 읽는지, 16비트인지 확인 안 함 | `mimeType`의 rate를 실제로 읽도록 파서 수정 + `rate=16000`/rate 없음/짝수 바이트 테스트 추가 → 통과 |
| AC-03 | 역할·순서·확정 여부 확인 없음 | 자막이 말한 순서대로(ME→BOT) 쌓이는지 테스트 추가 → 통과 |
| AC-08 | 예외만 확인, 상태·큐·핸들 불변은 확인 안 함 | 깨진 프레임 7종을 실제로 흘려 상태·큐·핸들 불변을 확인하는 테스트 추가 → 통과 |
| AC-14 | `effects`가 매 입력마다 덮여 마지막 입력만 검사됨 | 테스트가 효과를 누적하도록 고치고 GoAway까지 추가 → 통과 |
| AC-16 | 직접 쓰기가 프리셋 자리에 오는지 미확인 | CUSTOM 조립 순서 테스트 추가 → 통과 |
| AC-18 | 대체 문장이 상수 참조라 상수를 바꿔도 통과 | 문장 리터럴("코드는 화면에 띄웠어요") 검증 추가 → 통과 |
| AC-19 | 2000/3000 경계가 상수 참조 | 상수 값 범위 + 실제 길이 2,000~3,000 리터럴 검증 추가 → 통과 |
| AC-20 | `HandOff.matches()`는 실제 경로가 아니고, 실제 짝(대기 맵)은 테스트 없음 | 대기 맵을 `VoiceTurnRegistry`로 분리해 실제 경로로 쓰고, 짝·오배달·중복 전달 테스트 4개 추가 → 통과 |

그 외 검증자가 찾아낸 **실제 결함 3건**도 고쳤다.

1. **알림의 끊기/음소거가 화면이 꺼지면 죽었다** — 액티비티에 등록한 리시버로 받고 있었기 때문. `PendingIntent.getService`로 바꾸고 `VoiceService`가 `LiveController.running()`에 직접 전달하게 했다(AC-30·37).
2. **통화 화면을 떠나면 통화가 끊겼다** — `onDestroy`에서 `close()`를 부르고 있었다. `LiveController`를 프로세스 단일 인스턴스로 바꿔 화면을 떠나도 통화가 유지되고, 돌아오면 같은 소켓을 다시 쓴다(AC-36).
3. **정리 지점이 두 곳이었다** — `Ended`가 `Cleanup`과 `CloseSocket`을 함께 내보내 소켓을 두 번 닫았다. `Cleanup` 하나로 줄였고, 키가 없어 시작이 거부된 경로도 정리를 거치게 했다(AC-13).

### 규칙을 일부러 깨서 가드가 무는지 확인 (`docs/voice-mutation-log.txt`)

| 깬 규칙 | 관측 |
|---|---|
| 정리를 두 번 하도록 바꿈 | `LiveMachineTest > hang-up tears down once and names every resource` FAILED |
| 끼어들 때 대기 중 답을 버리도록 바꿈 | `LiveMachineTest > interrupting while Hermes works keeps the answer` FAILED |
| 90초 → 600초로 바꿈 | `LiveMachineTest > 90 seconds is the line between waiting and failing` FAILED |
| 항상 붙는 규칙을 빼도록 바꿈 | `PersonaTest` 4개 FAILED |
| 모두 되돌린 뒤 | BUILD SUCCESSFUL |

## 표 2. 스파이크 6개 — 확정 사실과 근거

| # | 확정 사실 | 근거 |
|---|---|---|
| S-1 | `behavior: NON_BLOCKING`을 받는다. 모델은 호출 직후 "잠깐만 확인해볼게~"를 말하고 턴을 닫은 뒤, 결과가 오면 새 턴으로 읽는다(응답→첫 음성 699·782 ms). 함수 선언에 `scheduling`은 **없는 필드**(거절), `toolResponse`에는 있는 필드 | `tools/spike_01_async_tool.py` / `.json` |
| S-2 | 연결 직후 0.5초 안에 36자 `newHandle`(resumable=true) 발급. 핸들로 새 연결에서 **맥락 유지 확인**("47이야."), 핸들 없으면 "기억나지 않아." **만료/위조 핸들은 1008로 거절**(→ 앱이 핸들을 버리고 새로 붙도록 구현). `goAway`는 120초 유휴에서 **관측 안 됨**. `triggerTokens` 필드는 없고 `targetTokens`만 받는다 | `tools/spike_02_session.py` / `.json` |
| S-3 | 기본 말 끝 감지 1,264 ms. `END_SENSITIVITY_HIGH`+500 ms = **1,042 ms**(첫 음성도 1,042 ms). HIGH+300은 608~816 ms. LOW+1,500은 2,100 ms. 무음을 실제로 보내지 않으면 ACTIVITY_END가 오지 않는다 | `tools/spike_03_vad.py`, `spike_03_conclude.py` |
| S-4 | 30개 중 **29개가 서로 다른 목소리**, 전부 한국어. **Iapetus는 Fenrir와 같은 지문**(대체음). 없는 이름도 오디오가 나오며 Zephyr 쪽에 가장 가까움(경계값) — "기본 목소리로 대체"는 확정 아님. F0는 실행마다 10~20 Hz 흔들림 | `tools/spike_04_voices.py`, `spike_04_report.txt`, `spike04_*.wav` |
| S-5 | iOS CallKit vs 라이브 액티비티 — **winter 결정 보류**(질문에 답하지 않음). iOS 착수 전에 정하면 됨 | 대화 기록 |
| S-6 | 무료 한도 숫자 — **모름으로 확정**(winter 지시). 429 안내만 제대로 만든다 | 대화 기록 |

## 실행

```bash
cd "/Users/kanyevelvet/Documents/Claude/hermes native" && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:assembleDebug --offline
adb install -r app/build/outputs/apk/debug/hermes-next-v1.0.0-debug.apk   # 패키지: dev.hermes.next.debug
```

디버그 빌드는 `dev.hermes.next.debug`로 따로 설치되어 기존 앱 데이터를 건드리지 않는다.
음성 버튼은 대화 입력칸이 비어 있을 때 보이고, 키가 없으면 설정 화면이 먼저 뜬다.

## 알려진 한계

1. **기기 시험이 하나도 안 돌았다.** 디버그 앱에 Gemini 키를 넣어야 하는데, 키 입력은 winter가 직접 해야 한다(에이전트가 자격증명을 타이핑하지 않는다). 폰이 사용 중이어서 화면 조작도 멈췄다.
2. **미구현 1건**: 근접 센서로 화면 끄기/수화기 전환(AC-38), 통화 중 "통화 중 03:12" 띠(AC-36의 띠 부분).
3. **독립 검증이 남긴 미구현 3건**(전부 AC-31·32·33·35 범위): 오디오 포커스를 되찾았을 때 재생은 복구하지 않고 `Delegating` 중에는 마이크를 다시 열지 않는다. 와이파이→LTE·블루투스 경로 변경을 감지하는 리스너가 없다(안드로이드 자동 라우팅에 맡김). 틀린 키 전용 문구가 없어 소켓 종료로만 드러난다.
4. **F0 수치는 실행마다 10~20 Hz 흔들린다**: 최종 스윕에서 Kore 199·Zephyr 199·Charon 116으로 계획서 값(223·213·135)과 7~14% 차이가 났다. 앱은 Hz를 보여주지 않고 "높은 편/중간/낮은 편"만 쓴다.
5. **"없는 목소리 이름은 기본 목소리로 대체"는 확정되지 않았다**: 없는 이름도 오디오가 나왔고 지문은 Zephyr에 가장 가까웠다(0.179, 경계값). 앱이 이름 입력을 막는 이유는 그대로 유효하다.

## 다음 할 일

1. 격리 저장소에 키를 넣고(겨울이 1회) 기기 시험 18개를 `docs/voice-device-plan.md` 순서로 돌린다.
2. AC-38(근접 센서)과 AC-36의 "통화 중" 띠를 구현한다.
3. Android 기기 시험이 끝나면 iOS E 단계(25~27)를 시작한다.
