"""Link original ARM objects using a local LLVM or GNU ARM linker."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def link(objects, output, script=None, address='0x20000', entry='_start'):
    linker = os.environ.get('ARM_LD') or shutil.which('ld.lld') or shutil.which('arm-linux-gnueabi-ld')
    if not linker:
        raise RuntimeError('Install LLVM lld or GNU ARM binutils, or set ARM_LD.')
    with tempfile.TemporaryDirectory(prefix='iap2-link-') as directory:
        command = [linker, '-m', 'armelf_linux_eabi', '-e', entry]
        if script:
            layout = Path(directory) / 'layout.ld'
            layout.write_text(script)
            command += ['-T', str(layout)]
        else:
            command += ['-Ttext=' + address]
        subprocess.run(command + ['-o', str(output)] + [str(p) for p in objects], check=True)
