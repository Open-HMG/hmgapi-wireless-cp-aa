"""Version 1 replay boundary. Android can implement ReplayBackend independently."""
from abc import ABC, abstractmethod
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
MOD = REPO / 'wireless-carplay-aa'
LIMIT = 1024 * 1024


class ReplayBackend(ABC):
    @abstractmethod
    def replay(self, scenario):
        """Return {schemaVersion: 1, backend: str, steps: [...]} from one fresh run."""

    @abstractmethod
    def describe(self):
        """Return backend identity and reproducibility metadata."""


def java_home(explicit=None):
    choices = [Path(p) for p in (explicit, os.environ.get('JAVA_HOME')) if p]
    choices += sorted((REPO / 'private/toolchains/temurin17').glob('*/Contents/Home'))
    found = shutil.which('javac')
    if found:
        choices.append(Path(found).resolve().parent.parent)
    for home in choices:
        if (home / 'bin/java').is_file() and (home / 'bin/javac').is_file():
            result = subprocess.run([str(home / 'bin/javac'), '-version'], capture_output=True, text=True)
            if result.returncode == 0:
                return home
    raise RuntimeError('JDK 17+ required. Pass --java-home, set JAVA_HOME, or use the private project toolchain.')


class JavaBackend(ReplayBackend):
    def __init__(self, home=None):
        self.home = java_home(home)
        self.temp = tempfile.TemporaryDirectory(prefix='palisade-simulator-')
        self.classes = Path(self.temp.name)
        handoff = MOD / 'tools/cluster-handoff/stubs'
        sources = sorted((HERE / 'java').rglob('*.java'))
        sources += sorted((handoff / 'android').rglob('*.java'))
        sources = [p for p in sources if p != handoff / 'android/os/SystemClock.java']
        sources += [
            handoff / 'hmgapi/hook/ModContext.java',
            handoff / 'hmgapi/mods/wireless/Wireless.java',
            MOD / 'tools/cpturns/stubs/android/os/SystemClock.java',
            MOD / 'tools/cpturns/stubs/hmgapi/mods/wireless/dipo/Dipo.java',
            MOD / 'tools/aaturns/stubs/android/text/format/DateFormat.java',
            MOD / 'stubs/com/tcc/iap2/Iap2NotiMsg.java',
            MOD / 'src/hmgapi/mods/wireless/dipo/CpTurns.java',
            MOD / 'src/hmgapi/mods/wireless/ClusterHandoff.java',
        ]
        self.hashes = {str(p.relative_to(REPO)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}
        command = [str(self.home / 'bin/javac'), '--release', '8', '-encoding', 'UTF-8', '-d', str(self.classes)]
        result = subprocess.run(command + [str(p) for p in sources], capture_output=True, text=True, timeout=60)
        if result.returncode:
            self.close()
            raise RuntimeError('Simulator compilation failed:\n' + result.stderr)

    def describe(self):
        return {'id': 'java-host', 'label': 'Java · production bridge', 'schemaVersion': 1,
                'sourceHashes': self.hashes,
                'limitations': ['Android IPC and message objects are modeled.',
                                'Display rendering is a model, not cluster/HUD firmware.',
                                'Inputs are scripted, not a live CarPlay session.']}

    def replay(self, scenario):
        payload = json.dumps(scenario, ensure_ascii=True, allow_nan=False)
        if len(payload.encode()) > LIMIT:
            raise ValueError('Scenario exceeds 1 MiB')
        result = subprocess.run([str(self.home / 'bin/java'), '-cp', str(self.classes),
                                 'hmgapi.mods.wireless.dipo.SimulatorRunner'], input=payload,
                                capture_output=True, text=True, timeout=20)
        if result.returncode:
            raise RuntimeError('Java replay failed:\n' + result.stderr[-4000:])
        replay = json.loads(result.stdout)
        if replay.get('schemaVersion') != 1 or len(replay.get('steps', [])) != len(scenario['events']):
            raise RuntimeError('Backend returned an incompatible replay')
        return replay

    def close(self):
        self.temp.cleanup()
