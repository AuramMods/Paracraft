#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Explicitly consumes the selected local ChatGPT plan session; no game window or browser.
mkdir -p build/qa
./gradlew --console=plain test build runGameTestServer -PliveChatgpt "$@" 2>&1 | tee build/qa/live.log
python3 - <<'PY'
import json
from pathlib import Path
events = []
for line in Path('build/qa/live.log').read_text().splitlines():
    if 'QA_EVT ' in line:
        events.append(json.loads(line.split('QA_EVT ', 1)[1]))
for required in ['live_luna_real_diamond_delivery', 'live_luna_native_crafting']:
    if not any(event.get('event') == required and event.get('status') == 'pass' for event in events):
        raise SystemExit(f'{required} did not pass; inspect build/qa/live.log and run/live-qa/client.log.')
print('Verified actual live Luna delivery and internal/table crafting. Credentials stayed in separate client JVMs.')
PY
