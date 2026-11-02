#!/usr/bin/env bash
set -Eeuo pipefail
ROOT=/home/ubuntu/print-system
WT="$ROOT/runtime/worktrees/backend-test-compile-20261102"
EVIDENCE="$ROOT/evidence/testing/r11-backend-test-compile-20261102"
mkdir -p "$EVIDENCE"
cd "$WT"
export MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m'
if [[ ${1:-all} != finalize ]]; then
  /usr/bin/time -v -o "$EVIDENCE/test-compile-time.txt" mvn -o -B -ntp -T 1 -pl yudao-server -am test-compile >"$EVIDENCE/test-compile.log" 2>&1
  /usr/bin/time -v -o "$EVIDENCE/package-time.txt" mvn -o -B -ntp -T 1 -pl yudao-server -am -DskipTests package >"$EVIDENCE/package.log" 2>&1
fi
python3 - "$WT" "$EVIDENCE" <<'PY'
import hashlib, json, pathlib, re, subprocess, sys, zipfile
wt, evidence = map(pathlib.Path, sys.argv[1:])
pom = wt / 'yudao-server/pom.xml'
jar = wt / 'yudao-server/target/yudao-server.jar'
with zipfile.ZipFile(jar) as z:
    forbidden = [n for n in z.namelist() if n.startswith('BOOT-INF/lib/') and re.search(r'(mockito|byte-buddy-agent)', n, re.I)]
if forbidden:
    raise SystemExit('TEST_DEPENDENCY_LEAK_IN_PRODUCTION_JAR: ' + repr(forbidden))
matches = []
source_count = len(list((wt / 'yudao-server/src/test').rglob('*.java')))
test_classes = list((wt / 'yudao-server/target/test-classes').rglob('*.class'))
summary = {
    'schemaVersion': 1,
    'status': 'PASS_TEST_COMPILATION_AND_PACKAGE_ONLY',
    'backendBaseCommit': '7efd316fb154c65fff089da7a5919284fb1f8cd6',
    'backendWorktree': str(wt),
    'change': 'mockito-core test scope dependency with BOM-managed version; exclude transitive objenesis to preserve existing runtime version',
    'testSourceCount': source_count,
    'compiledTestClassCount': len(test_classes),
    'testCompile': 'PASS',
    'packageWithSkipTests': 'PASS',
    'dependencyScope': 'test',
    'dependencyTreeLines': matches,
    'dependencyTreeStatus': 'FAILED_OFFLINE_NOT_PROOF',
    'productionJarForbiddenTestLibraries': forbidden,
    'jarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
    'jarBytes': jar.stat().st_size,
    'pomSha256': hashlib.sha256(pom.read_bytes()).hexdigest(),
    'printingMainChecksExecution': 'NOT_RUN',
    'upstreamJUnitExecution': 'NOT_RUN',
    'deployment': 'NOT_RUN',
    'model': 'gpt-6.1-sol',
    'effort': 'INHERITED_PARENT_NOT_SEPARATELY_EXPOSED',
    'boundary': 'test-compile proves compilation only; -DskipTests package does not execute printing *Checks main methods or JUnit suites'
}
(evidence / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary, indent=2))
PY
