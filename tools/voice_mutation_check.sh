#!/bin/bash
# Prove the guards bite: break one rule at a time, show the named test fail, restore, show it pass.
# Every edit is reverted from a backup copy, so nothing is left changed.
set -u
ROOT="<repo>"
cd "$ROOT" || exit 1
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
MACHINE="app/src/main/java/com/hermeswebui/android/assistant/voice/LiveMachine.kt"
PERSONA="app/src/main/java/com/hermeswebui/android/assistant/voice/Persona.kt"
OUT="$ROOT/docs/voice-mutation-log.txt"
: > "$OUT"

run_case () {
  local name="$1" filter="$2" expect="$3"
  local result
  result=$(./gradlew :app:testDebugUnitTest --offline --tests "$filter" 2>&1 | grep -E "FAILED|BUILD" | head -12)
  echo "### $name" | tee -a "$OUT"
  echo "expected failing test contains: $expect" | tee -a "$OUT"
  echo "$result" | tee -a "$OUT"
  if echo "$result" | grep -q "$expect"; then
    echo "=> VERDICT: the guard caught the broken rule (test failed as named)" | tee -a "$OUT"
  else
    echo "=> VERDICT: NO FAILURE SEEN — the guard is weaker than claimed" | tee -a "$OUT"
  fi
  echo | tee -a "$OUT"
}

# 1. one teardown, not two
cp "$MACHINE" /tmp/LiveMachine.bak
python3 - "$MACHINE" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1]); s = p.read_text()
s = s.replace("return Transition(next, listOf(VoiceEffect.Cleanup(reason)))",
              "return Transition(next, listOf(VoiceEffect.Cleanup(reason), VoiceEffect.Cleanup(reason)))")
p.write_text(s)
PY
run_case "rule: Ended cleans up exactly once" "com.hermeswebui.android.assistant.voice.LiveMachineTest" "tears down once"
cp /tmp/LiveMachine.bak "$MACHINE"

# 2. interruption must not drop the Hermes answer
python3 - "$MACHINE" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1]); s = p.read_text()
s = s.replace("val phase = if (state.pendingCall != null) state.phase else VoicePhase.Listening\n            Transition(\n                committed.copy(phase = phase, queuedChunks = 0),",
              "val phase = VoicePhase.Listening\n            Transition(\n                committed.copy(phase = phase, queuedChunks = 0, pendingCall = null),")
p.write_text(s)
PY
run_case "rule: an interruption keeps the pending Hermes answer" "com.hermeswebui.android.assistant.voice.LiveMachineTest" "keeps the answer"
cp /tmp/LiveMachine.bak "$MACHINE"

# 3. the 90 second line
python3 - "$MACHINE" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1]); s = p.read_text()
s = s.replace("if (now - pending.startedAt < HermesHandOff.TIMEOUT_MS) return Transition(state, emptyList())",
              "if (now - pending.startedAt < 600_000) return Transition(state, emptyList())")
p.write_text(s)
PY
run_case "rule: 90 s is the delegation deadline" "com.hermeswebui.android.assistant.voice.LiveMachineTest" "90 seconds"
cp /tmp/LiveMachine.bak "$MACHINE"

# 4. the always-rules are never lost
cp "$PERSONA" /tmp/Persona.bak
python3 - "$PERSONA" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1]); s = p.read_text()
s = s.replace("        blocks += ALWAYS_RULES.joinToString(separator = \"\\n\")", "        // rules removed on purpose")
p.write_text(s)
PY
run_case "rule: every prompt keeps the four always-rules" "com.hermeswebui.android.assistant.voice.PersonaTest" "keeps all four rules"
cp /tmp/Persona.bak "$PERSONA"

echo "=== restored; final verification run ===" | tee -a "$OUT"
./gradlew :app:testDebugUnitTest --offline --tests 'com.hermeswebui.android.assistant.voice.*' 2>&1 | grep -E "FAILED|BUILD" | tee -a "$OUT"
git -C "$ROOT" status --short | head -5 | tee -a "$OUT"
