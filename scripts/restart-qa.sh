#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# This workspace is exclusively generated QA data, distinct from ordinary dev worlds.
python3 - <<'PY'
import shutil
shutil.rmtree('run/restart-qa', ignore_errors=True)
PY
mkdir -p build/qa/restart
./gradlew --console=plain -PrestartPhase=write runGameTestServer 2>&1 | tee build/qa/restart/write.log
./gradlew --console=plain -PrestartPhase=read runGameTestServer 2>&1 | tee build/qa/restart/read.log
python3 - <<'PY'
import json
from pathlib import Path
events = {}
for phase in ['write', 'read']:
    for line in Path(f'build/qa/restart/{phase}.log').read_text().splitlines():
        if 'QA_EVT ' in line:
            event = json.loads(line.split('QA_EVT ', 1)[1])
            if event.get('event') == f'cold_restart_{phase}' and event.get('status') == 'pass':
                events[phase] = event
if set(events) != {'write', 'read'}:
    raise SystemExit('Restart QA did not execute both required world assertions; see write.log/read.log')
if events['write']['context']['pid'] == events['read']['context']['pid']:
    raise SystemExit('Restart QA must use two distinct Minecraft JVMs')
print('Verified actual write/read assertions in distinct Forge JVMs.')
PY
