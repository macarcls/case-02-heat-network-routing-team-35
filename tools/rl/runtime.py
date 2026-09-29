"""Launch the Java engineering environment from a built server JAR (Java 11+, Python 3.9+)."""
import contextlib
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]


@contextlib.contextmanager
def command():
    jars = sorted((ROOT / 'server/target').glob('teplotrassa-server-*.jar'))
    if not jars:
        jars = sorted((ROOT / 'release').glob('teplotrassa-server-*.jar'))
    if len(jars) != 1:
        raise RuntimeError('Build first: mvn -f server/pom.xml -DskipTests package (one server JAR required)')
    java_home = os.environ.get('JAVA_HOME')
    java = str(Path(java_home) / 'bin/java') if java_home else 'java'
    javac = str(Path(java_home) / 'bin/javac') if java_home else 'javac'
    with tempfile.TemporaryDirectory(prefix='teplotrassa-rl-') as temporary:
        base = Path(temporary)
        libs, classes = base / 'lib', base / 'classes'
        libs.mkdir(); classes.mkdir()
        with zipfile.ZipFile(jars[0]) as archive:
            for name in archive.namelist():
                if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                    (libs / Path(name).name).write_bytes(archive.read(name))
                elif name.startswith('BOOT-INF/classes/') and not name.endswith('/'):
                    target = classes / name.removeprefix('BOOT-INF/classes/')
                    if not target.resolve().is_relative_to(classes.resolve()):
                        raise ValueError('Invalid JAR path')
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(archive.read(name))
        cp = os.pathsep.join([str(classes), str(libs / '*')])
        subprocess.run([javac, '--release', '11', '-cp', cp, '-d', str(classes),
                        str(ROOT / 'tools/neural/NeuralExperiment.java'),
                        str(ROOT / 'tools/rl/RlBridge.java'),
                        str(ROOT / 'tools/rl/ActiveSearchCheck.java')], check=True, cwd=ROOT)
        yield [java, '-Xmx768m', '-cp', cp, 'ru.teplotrassa.experiment.RlBridge']


if __name__ == '__main__':
    import sys
    with command() as args:
        if len(sys.argv) > 1 and sys.argv[1] == 'active-search':
            args[-1] = 'ru.teplotrassa.experiment.ActiveSearchCheck'
            subprocess.run(args + sys.argv[2:], check=True, cwd=ROOT)
        else:
            subprocess.run(args + sys.argv[1:], check=True, cwd=ROOT)
