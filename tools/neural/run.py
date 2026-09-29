"""Run the reproducible Java experiment against the compiled application.

python3 tools/neural/run.py collect tools/neural/data 0 80
python3 tools/neural/run.py benchmark validation/neural 64 16
python3 tools/neural/run.py supplied validation/neural on
Build first: mvn -f server/pom.xml -DskipTests package
Uses JAVA_HOME when set. Python here only launches Java; no ML packages required.
"""
import os
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[2]
jar = root / 'server/target/teplotrassa-server-1.5.0-tree-learning.jar'
if not jar.is_file():
    raise SystemExit('Build first: mvn -f server/pom.xml -DskipTests package')
java_bin = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else None
java = str(java_bin / 'java') if java_bin else 'java'
javac = str(java_bin / 'javac') if java_bin else 'javac'
with tempfile.TemporaryDirectory(prefix='teplotrassa-neural-') as temporary:
    base = Path(temporary)
    libs, classes = base / 'lib', base / 'classes'
    libs.mkdir(); classes.mkdir()
    with zipfile.ZipFile(jar) as archive:
        for name in archive.namelist():
            if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                (libs / Path(name).name).write_bytes(archive.read(name))
            elif name.startswith('BOOT-INF/classes/') and not name.endswith('/'):
                target = classes / name.removeprefix('BOOT-INF/classes/')
                if not target.resolve().is_relative_to(classes.resolve()):
                    raise ValueError('Invalid path inside application jar')
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.read(name))
    cp = os.pathsep.join([str(classes), str(libs / '*')])
    subprocess.run([javac, '--release', '11', '-cp', cp, '-d', str(classes),
                    str(root / 'tools/neural/NeuralExperiment.java')], check=True, cwd=root)
    subprocess.run([java, '-Xmx768m', '-cp', cp, 'ru.teplotrassa.experiment.NeuralExperiment',
                    *sys.argv[1:]], check=True, cwd=root)
