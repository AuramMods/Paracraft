#!/usr/bin/env python3
"""Run the real windowless QA commands in an owned background process group (macOS/Linux)."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parent.parent
DIRECTORY = ROOT / 'build/qa/background'
STATE = DIRECTORY / 'state.json'
LOCK = DIRECTORY / 'worker.lock'
LOG = DIRECTORY / 'output.log'


def save(value):
    temporary = STATE.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, indent=2) + '\n')
    temporary.replace(STATE)


def read():
    try:
        return json.loads(STATE.read_text())
    except FileNotFoundError:
        return {'phase': 'never_started'}


def lock_is_held():
    with LOCK.open('a') as handle:
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return True
        return False


def group_is_alive(group):
    try:
        os.killpg(group, 0)
        return True
    except ProcessLookupError:
        return False


def start(mode):
    handle = LOCK.open('a')
    try:
        # An actual held resource prevents overlapping workers; no cached PID/port guess gates execution.
        fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        raise SystemExit('A QA worker holds the execution lock. Use status or stop.')
    run_id = str(uuid.uuid4())
    save({'runId': run_id, 'phase': 'starting', 'mode': mode, 'log': str(LOG)})
    try:
        with LOG.open('w') as output:
            worker = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), '_worker',
                                       '--lock-fd', str(handle.fileno()), '--run-id', run_id, '--mode', mode],
                                      cwd=ROOT, stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT,
                                      pass_fds=(handle.fileno(),), start_new_session=True)
    except Exception as failure:
        save({'runId': run_id, 'phase': 'launch_failed', 'error': str(failure), 'mode': mode})
        raise
    finally:
        handle.close()
    print(json.dumps({'workerPid': worker.pid, 'runId': run_id, 'mode': mode, 'log': str(LOG)}))


def worker(args):
    # The inherited descriptor owns the lock until this worker and its wait have ended.
    with os.fdopen(args.lock_fd, 'a'):
        command = ['./scripts/restart-qa.sh' if args.mode == 'restart' else './scripts/qa.sh']
        state = {'runId': args.run_id, 'phase': 'running', 'mode': args.mode, 'workerPid': os.getpid(),
                 'startedAt': time.time(), 'command': command, 'log': str(LOG)}
        child = None
        stopping = False

        def request_stop(_signal, _frame):
            nonlocal stopping
            stopping = True
            if child is not None and child.poll() is None:
                try:
                    os.killpg(child.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass

        signal.signal(signal.SIGTERM, request_stop)
        signal.signal(signal.SIGINT, request_stop)
        save(state)
        try:
            if stopping:
                code = -signal.SIGTERM
            else:
                child = subprocess.Popen(command, cwd=ROOT, stdin=subprocess.DEVNULL, start_new_session=True)
                state['processGroup'] = child.pid
                save(state)
                if stopping:
                    request_stop(signal.SIGTERM, None)
                code = child.wait()
            state.update(phase='cancelled' if stopping else 'passed' if code == 0 else 'failed',
                         exitCode=code, finishedAt=time.time())
        except Exception as failure:
            request_stop(signal.SIGTERM, None)
            if child is not None:
                child.wait()
            state.update(phase='failed', error=str(failure), finishedAt=time.time())
        if stopping and child is not None:
            # The shell can exit before Java's shutdown hook finishes saving its disposable world.
            # Retain exclusive ownership until the actual process group drains.
            deadline = time.monotonic() + 30
            while group_is_alive(child.pid) and time.monotonic() < deadline:
                time.sleep(0.1)
            if group_is_alive(child.pid):
                try:
                    os.killpg(child.pid, signal.SIGKILL)
                    state['forcedShutdown'] = True
                except ProcessLookupError:
                    pass
            state['finishedAt'] = time.time()
        save(state)


def status():
    state = read()
    state['workerLockHeld'] = lock_is_held()
    if not state['workerLockHeld'] and state['phase'] in ('starting', 'running'):
        state['phase'] = 'exited_without_result'
    print(json.dumps(state, indent=2))


def stop():
    if not lock_is_held():
        print('No background QA worker is running.')
        return
    state = read()
    for _ in range(20):
        if state.get('workerPid') is not None:
            break
        time.sleep(0.1)
        state = read()
    pid = state.get('workerPid')
    if pid is None:
        raise SystemExit('Worker is still starting; retry stop shortly.')
    actual = subprocess.run(['ps', '-ww', '-p', str(pid), '-o', 'command='], capture_output=True, text=True)
    if str(Path(__file__).resolve()) not in actual.stdout or '_worker' not in actual.stdout or state['runId'] not in actual.stdout:
        raise SystemExit('The recorded PID does not identify this QA worker; no signal was sent.')
    try:
        os.kill(pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    print('Requested graceful shutdown of the owned QA process group. Check status and output.log.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['start', 'status', 'stop', '_worker'])
    parser.add_argument('--mode', choices=['normal', 'restart'], default='normal')
    parser.add_argument('--lock-fd', type=int)
    parser.add_argument('--run-id')
    args = parser.parse_args()
    DIRECTORY.mkdir(parents=True, exist_ok=True)
    if args.action == '_worker':
        worker(args)
    elif args.action == 'start':
        start(args.mode)
    elif args.action == 'status':
        status()
    else:
        stop()


if __name__ == '__main__':
    main()
