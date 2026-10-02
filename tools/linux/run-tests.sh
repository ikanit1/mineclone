#!/usr/bin/env bash
# Compile the repository with Java 17 bytecode and run its zero-dependency suite.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
CLASSES=${CLASSES:-$ROOT/build/linux-test-classes}
task_work=out-test/linux-test-build
mkdir -p "$task_work/generated/com/mineclone/core" "$CLASSES"
python3 - <<'PY'
from pathlib import Path
import subprocess
version=next(line.split('=',1)[1].strip() for line in Path('gradle.properties').read_text().splitlines() if line.startswith('version='))
sha=subprocess.check_output(['git','rev-parse','--short=12','HEAD'],text=True).strip()
template=Path('src/main/java/com/mineclone/core/BuildInfo.java.template').read_text()
Path('out-test/linux-test-build/generated/com/mineclone/core/BuildInfo.java').write_text(template.replace('@VERSION@',version).replace('@GIT_SHA@',sha))
PY
rg --files src/main/java src/test/java -g '*.java' > "$task_work/sources.txt"
printf '%s\n' "$task_work/generated/com/mineclone/core/BuildInfo.java" >> "$task_work/sources.txt"
task_java=java;task_javac=javac
if [ -n "${JAVA_HOME:-}" ]; then task_java="$JAVA_HOME/bin/java";task_javac="$JAVA_HOME/bin/javac";fi
"$task_javac" --release 17 -encoding UTF-8 -d "$CLASSES" -cp 'libs/*' @"$task_work/sources.txt"
"$task_java" -cp "$CLASSES:libs/*" com.mineclone.TestMain "$@"
