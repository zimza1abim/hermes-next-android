# 스파이크 결과 — 기획서 「구현 전에 확인할 것」

측정 환경: macOS, 무료 키(`~/.config/hermes-next/gemini.key`), 모델 `gemini-3.8-live`, 2026-11-18 KST.
모든 수치는 이 저장소 `tools/`의 스크립트를 실제로 돌려 나온 출력이다. 관측하지 못한 항목은 "관측 안 됨"으로 적었다.

## S-1. 기다리지 않는 함수 호출과 결과 전달 시점

스크립트: `tools/spike_01_async_tool.py` · 결과: `tools/spike_01_async_tool.json`

| 선언 | 서버 응답 | 근거 |
|---|---|---|
| `behavior: "NON_BLOCKING"` | **받는다** | setup 통과, 아래 타이밍이 이 선언으로 측정됨 |
| `behavior: "BLOCKING"` | 받는다 | 대조군으로 측정 |
| `behavior: "ASYNC"` | 거절 | `Invalid value at 'setup.tools[0].function_declarations[0].behavior'` |
| 함수 선언에 `scheduling` | **거절** | `Invalid JSON payload ... Unknown name "scheduling" at 'setup.tools[0].function_declarations[0]'` |
| `toolResponse`에 `scheduling` | 필드는 있고 값만 검사 | `Invalid value at 'tool_response.function_responses[0].scheduling'` |

측정(비동기 선언, 응답을 5초/15초 뒤에 보냄):

| 지연 | 함수 호출 도착 | 첫 음성(호출 전) | 결과 → 첫 음성 | 턴 종료가 응답보다 먼저? | 모델이 먼저 한 말 |
|---|---|---|---|---|---|
| 5초 | 735 ms | 509 ms | **699 ms** | **예** | "잠깐만 확인해볼게~" |
| 15초 | 894 ms | 741 ms | 782 ms | 예 | "잠깐만 확인해볼게~" |

**확정**: 9번(비동기 넘기기) 설계를 바꾸지 않는다. 모델은 호출 직후 "잠깐만 확인해볼게~"를 말하고
턴을 닫은 뒤, 결과가 오면 새 턴으로 읽는다. 앱은 **결과를 기다리는 동안 소켓을 붙들고 있을 필요가 없다**.
결과 전달 지연은 0.7초 안쪽이므로 체감 대기 없음.

추가로 확인된 두 가지(최종 스윕):
- **결과를 기다리는 동안 모델은 스스로 말을 잇지 않는다**(대기 중 오디오 0B, "계속 말해" 지시에도 0B). 그래서 대기 중 대화를 채우는 건 앱 몫이다.
- 앱이 그 사이에 별도 턴을 말하게 하면(끼어들기) 그건 동작하고 135KB가 나오지만, **늦게 온 결과는 곧바로 낭독되지 않고 다음 넛지 때 읽힌다**(4/4회, 넛지 후 728~860ms). 결과를 잃지는 않지만 한 박자 늦는다.

## S-2. 세션 길이·재개 핸들·종료 예고·맥락 압축

스크립트: `tools/spike_02_session.py` · 결과: `tools/spike_02_session.json`

| 항목 | 관측 |
|---|---|
| `sessionResumption: {}` | 받는다. 연결 직후 **0.5초 안에** `newHandle`(36자 UUID)과 `resumable: true`를 준다 |
| 핸들 갱신 | 세션 내내 1회만 발급됨(이번 표본). 대화가 늘어도 새 핸들이 계속 오지는 않음 |
| `sessionResumption: {handle}` | 받는다. 새 연결이 즉시 새 핸들을 받는다 |
| **맥락 유지** | A 세션에서 "좋아하는 숫자는 47" → 소켓 닫음 → B 세션에 핸들로 재접속 → **"47이야."** 라고 답함 (증거: `resume_test.session_b.post.turns[0].out`) |
| `goAway` | **120초 유휴 동안 관측 안 됨**. 세션 길이 한도 숫자는 이번 시험으로 확인 불가 |
| `contextWindowCompression.slidingWindow` | 받는다. 6턴 이상 정상 진행(턴 2회 측정: 7.4초·4.0초) |

추가로 확인된 것(최종 스윕):
- **잘못된/만료된 핸들은 close 1008 "BidiGenerateContent session not found"로 거절된다.** 핸들을 계속 들고 재시도하면 영원히 같은 실패를 반복하므로, 앱은 1008을 보면 핸들을 버리고 새 세션으로 붙어야 한다(구현·테스트에 반영).
- `contextWindowCompression.slidingWindow`에는 `triggerTokens`가 **없다**(1007 Unknown name). `targetTokens`만 쓴다.
- 서버는 빈 `{}` 프레임을 섞어 보낸다(6턴에 75개). 파서가 빈 메시지를 무시해야 한다.

**확정**: 18번(재개) 설계 유지. 종료 예고는 "언젠가 온다"는 가정 위에 두되, 안 와도 통화가 계속되도록
설계하고, 핸들은 `resumable: true`일 때만 저장한다. 세션 길이 한도는 **미확인**으로 남긴다.

## S-3. 자동 말 끝 감지 기본값과 민감도

스크립트: `tools/spike_03_vad.py`, `tools/spike_03_conclude.py` · 결과: 같은 이름의 `.json`

| 설정 | 말 끝 감지 (마지막 음성 이후) | 첫 음성 |
|---|---|---|
| 기본값(아무것도 안 보냄) | 1,264 ms | 1,268 ms |
| `END_SENSITIVITY_LOW` + 1,500 ms | 2,100 ms | 2,102 ms |
| `END_SENSITIVITY_LOW` + 2,500 ms | 3,110 ms | 3,110 ms |
| `END_SENSITIVITY_HIGH` + 300 ms | 608 ms (1차) / 803 ms (2차) | 1,226 ms |
| **`END_SENSITIVITY_HIGH` + 500 ms** | **1,042 ms** | **1,042 ms** |
| 수동 모드(`disabled: true` + activityStart/End) | activityEnd 즉시(184 ms) | 581 ms |

받는 필드: `startOfSpeechSensitivity`, `endOfSpeechSensitivity`, `prefixPaddingMs`, `silenceDurationMs`
(모두 통과). 없는 enum 값(`END_SENSITIVITY_MEDIUM`)은 `Invalid value ... end_of_speech_sensitivity`로 거절.

**확정**: 앱 기본값은 **HIGH + 500 ms**. 기획서 목표(1.5초) 안에 들어오고, 기본값(1.26초)보다 0.2초 빠르다.
300 ms는 0.8초로 더 빠르지만 문장 중간 쉼에서 끊길 위험이 커서 기본값으로 쓰지 않는다.
`tools/spike_03_conclude.py`의 교훈: **무음을 실제로 보내지 않으면 ACTIVITY_END가 오지 않는다**(처음 측정이 실패한 이유).

## S-4. 전체 목소리 목록과 한국어 품질

스크립트: `tools/spike_04_voices.py` · 결과: `tools/spike_04_voices.json`, `tools/spike04_<이름>.wav`

30개 이름을 같은 한국어 문장으로 낭독시켜 LTAS(장기 평균 스펙트럼) 지문으로 묶었다(같은 목소리 0.03~0.08, 다른 목소리 0.18 이상). 최종 스윕은 빈 응답을 재시도해 31개 표본을 모두 채웠다.

| 목소리 | F0 | 판정 |
|---|---|---|
| Achernar | 273.7 Hz | 구분됨 |
| Laomedeia | 233.2 Hz | 구분됨 |
| Despina | 220.0 Hz | 구분됨 |
| Autonoe | 208.1 Hz | 구분됨 |
| Sadachbia | 204.7 Hz | 구분됨(1차에서 6ms 실패 → 재시도로 정상) |
| Leda | 203.6 Hz | 계획서 209 Hz와 3% 이내 |
| Sulafat | 202.7 Hz | 구분됨 |
| Umbriel | 201.9 Hz | 구분됨 |
| Callirrhoe | 200.3 Hz | 구분됨 |
| Kore | 199.3 Hz | 계획서 223 Hz와 11% 차이 |
| Zephyr | 198.8 Hz | 계획서 213 Hz와 7% 차이 |
| Vindemiatrix | 194.8 Hz | 구분됨 |
| Aoede | 187.4 Hz | 계획서 194 Hz와 3% 이내 |
| Erinome | 184.6 Hz | 구분됨 |
| Rasalgethi | 178.8 Hz | 구분됨 |
| Schedar | 166.4 Hz | 구분됨 |
| Orus | 155.0 Hz | 구분됨 |
| Zubenelgenubi | 144.5 Hz | 구분됨 |
| Pulcherrima | 138.7 Hz | 구분됨 |
| Gacrux | 133.2 Hz | 구분됨 |
| Achird | 130.6 Hz | 구분됨 |
| Fenrir | 128.4 Hz | 구분됨 |
| Puck | 120.3 Hz | 계획서 113 Hz와 7% 차이 |
| Enceladus | 119.3 Hz | 구분됨 |
| Charon | 115.7 Hz | 계획서 135 Hz와 14% 차이 |
| Algieba | 115.5 Hz | 구분됨 |
| Alnilam | 107.5 Hz | 구분됨 |
| Sadaltager | 96.9 Hz | 구분됨(재시도 후 22초 정상) |
| Algenib | 75.1 Hz | 구분됨 |

제외·주의:
- **Iapetus** — 지문이 Fenrir와 같다(LTAS 0.121). 진짜 목소리가 아니라 대체음이라 목록에서 뺐다.
- `NoSuchVoiceXYZ`(없는 이름) — 오류 없이 22초 오디오가 나왔고, 지문은 Zephyr와 0.179로 가장 가까웠다(경계값). **"없는 이름은 조용히 기본 목소리가 된다"는 가정은 확정된 게 아니라 Zephyr 쪽이라는 정황만 있다.** 그래서 앱은 이름 입력을 받지 않는다.
- F0는 같은 목소리도 실행마다 10~20 Hz 흔들린다. 화면에 숫자를 쓰지 않는 이유다.

한국어 품질: 31개 표본 전부 한국어(42~69자), 깨진 발음 0건. 실패 모드는 빈 응답뿐이었고 재시도 14회로 모두 복구됐다.

**앱 반영**: 측정으로 구분된 **29개**를 F0 순으로 고정 목록에 넣었다. 화면에는 Hz 대신 "높은 편/중간/낮은 편"만 보여 준다.

## S-5. iOS: CallKit vs 라이브 액티비티 — winter 결정 (선택지와 근거는 보고에)

## S-6. 무료 등급 실제 한도 — winter 결정 (AI Studio 화면에서만 확인 가능)
