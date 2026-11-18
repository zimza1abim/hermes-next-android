# 기기 시험 18개 — 실행 순서와 측정 지점

준비(겨울이 1회): 디버그 앱 `dev.hermes.next.debug` 를 열고 **음성 대화 설정 → Gemini 키** 에 키를 붙여 넣는다.
(에이전트는 자격증명을 대신 입력하지 않는다.)

측정은 앱이 남기는 표식으로 한다. 기획서 「측정」의 지점을 그대로 찍는다.

```bash
adb logcat -c
adb logcat -s HermesVoice        # 표식: speech_end, first_audio, interrupted, playback_cleared,
                                 #        tool_call, hermes_ask, hermes_answer, result_sent, cleaned_up
```

| # | AC | 하는 일 | 측정·판정 |
|---|---|---|---|
| 1 | AC-22 | 조용한 방, 스피커로 한 문장 말하기 | `first_audio` − `speech_end` ≤ 1,500 ms |
| 2 | AC-23 | 스피커를 크게 하고 봇이 말하는 동안 | 봇 발화로 `interrupted`/`speech_end`가 찍히지 않아야 통과 |
| 3 | AC-24 | 봇이 말하는 중에 끼어들기 | `playback_cleared` − `speech_end` ≤ 300 ms |
| 4 | AC-25 | "내 서버 디스크 용량 알려줘" | `tool_call` → `hermes_ask` → `hermes_answer` → `result_sent`가 순서대로, 대화에 질문·답이 남음 |
| 5 | AC-26 | 30초 넘는 Hermes 작업 시키기 | `hermes_ask` 이후에도 대화가 이어지고, `hermes_answer` 뒤에 결과 낭독 |
| 6 | AC-27 | 승인 필요한 도구 시키기 | 통화 화면에 카드, 허용 후 진행 |
| 7 | AC-28 | 목소리·말투 바꾸고 다음 통화 | 미리 듣기와 같은 목소리·말투 |
| 8 | AC-29 | 봇 두 개에 다른 목소리 지정 | 봇을 바꿔 통화하면 그 봇 목소리 |
| 9 | AC-30 | 통화 중 화면 끄기, 다른 앱 열기 | `adb shell dumpsys activity services dev.hermes.next.debug \| grep VoiceService` 유지, 알림에 끊기 |
| 10 | AC-31 | 통화 중 진짜 전화 받기 | 멈췄다가 통화 종료 후 이어짐 |
| 11 | AC-32 | 와이파이 끄고 LTE로 | `connected`가 다시 찍히고 맥락 유지(직전 내용 언급) |
| 12 | AC-33 | 블루투스 이어폰 연결/해제 | 소리가 그 장치로 이동 |
| 13 | AC-34 | 15분 넘게 통화 | 15:00 이후에도 `speech_end`/`first_audio` 계속 |
| 14 | AC-35 | ①키 지우고 통화 ②틀린 키 ③한도(429) | ①설정 화면으로 안내 ②오류 안내 ③"오늘 무료 한도를 다 썼어요" |
| 15 | AC-36 | 통화 중 뒤로 → 알림으로 복귀 | 통화 유지, 복귀 시 타이머 계속 (띠는 미구현) |
| 16 | AC-37 | 잠금 화면·상태바 | 통화 시간과 끊기 버튼, 끊기 동작 |
| 17 | AC-38 | 귀에 대기 | **미구현** — 근접 센서 처리 없음 |
| 18 | AC-39 | 통화 10번 열고 닫기 | `adb shell dumpsys meminfo dev.hermes.next.debug \| grep TOTAL` 10회 전/후 비교, 종료 후 `dumpsys audio \| grep -i record`에 남는 캡처 없음 |

13번(15분)과 18번(10회)은 시간이 걸리므로 따로 돌린다. 13번 도중 `goAway`가 오면 `connected`가 다시 찍히는지 본다.
