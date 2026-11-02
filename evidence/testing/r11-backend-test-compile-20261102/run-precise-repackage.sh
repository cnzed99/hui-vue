#!/usr/bin/env bash
set -Eeuo pipefail
ROOT=/home/ubuntu/print-system
WT="$ROOT/runtime/worktrees/backend-test-compile-20261102"
TARGET="$WT/yudao-server/target"
EVIDENCE="$ROOT/evidence/testing/r11-backend-test-compile-20261102/precise-repackage-20261103"
mkdir -p "$EVIDENCE"
test ! -L "$TARGET"
test "$(realpath -e "$TARGET")" = /home/ubuntu/print-system/runtime/worktrees/backend-test-compile-20261102/yudao-server/target
for file in "$TARGET/yudao-server.jar" "$TARGET/yudao-server.jar.original"; do
  test ! -L "$file"
  test "$(realpath -e "$file")" = "$file"
  stat -c '%n %s %Y' "$file"
done > "$EVIDENCE/removed-artifact-metadata.txt"
date +%s > "$EVIDENCE/start-epoch.txt"
rm -f -- "$TARGET/yudao-server.jar" "$TARGET/yudao-server.jar.original"
test ! -e "$TARGET/yudao-server.jar"
test ! -e "$TARGET/yudao-server.jar.original"
cd "$WT"
export MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m'
/usr/bin/time -v -o "$EVIDENCE/test-compile-time.txt" mvn -o -B -ntp -T 1 -pl yudao-server -am test-compile >"$EVIDENCE/test-compile.log" 2>&1
/usr/bin/time -v -o "$EVIDENCE/package-time.txt" mvn -o -B -ntp -T 1 -pl yudao-server -am -DskipTests package >"$EVIDENCE/package.log" 2>&1
grep -F "Building jar: $TARGET/yudao-server.jar" "$EVIDENCE/package.log" > "$EVIDENCE/new-jar-build-line.txt"
test "$(stat -c %Y "$TARGET/yudao-server.jar")" -gt "$(cat "$EVIDENCE/start-epoch.txt")"
python3 - "$WT" "$EVIDENCE" "$TMPDIR" <<'PY'
import hashlib,io,json,pathlib,re,sys,zipfile
wt,evidence,scratch=map(pathlib.Path,sys.argv[1:])
root=pathlib.Path('/home/ubuntu/print-system')
base=root/'runtime/releases/current/backend.jar'; cand=wt/'yudao-server/target/yudao-server.jar'
libs=scratch/'mockito-runtime-libs'; libs.mkdir()
with zipfile.ZipFile(base) as a,zipfile.ZipFile(cand) as b:
    na={n for n in a.namelist() if n.startswith('BOOT-INF/lib/') and not n.endswith('/')}
    nb={n for n in b.namelist() if n.startswith('BOOT-INF/lib/') and not n.endswith('/')}
    external=[]; internal=[]; differences=[]
    for n in sorted(na|nb):
        row={'name':n,'baselineSha256':hashlib.sha256(a.read(n)).hexdigest() if n in na else None,'candidateSha256':hashlib.sha256(b.read(n)).hexdigest() if n in nb else None}
        if pathlib.PurePosixPath(n).name.startswith('yudao-'):
            if row['baselineSha256']!=row['candidateSha256']:
                with zipfile.ZipFile(io.BytesIO(a.read(n))) as ia,zipfile.ZipFile(io.BytesIO(b.read(n))) as ib:
                    ka=set(ia.namelist()); kb=set(ib.namelist())
                    row.update({'innerAdded':sorted(kb-ka),'innerRemoved':sorted(ka-kb),'innerChanged':[p for p in sorted(ka&kb) if ia.read(p)!=ib.read(p)]})
                differences.append(row)
            internal.append(row)
        else: external.append(row)
    external_equal=all(v['baselineSha256']==v['candidateSha256'] for v in external)
    internal_content_equal=all(not v['innerAdded'] and not v['innerRemoved'] and all(p.endswith('/pom.properties') for p in v['innerChanged']) for v in differences)
    forbidden=[n for n in nb if re.search(r'(mockito|byte-buddy-agent)',n)]
    required={'BOOT-INF/lib/objenesis-3.4.jar','BOOT-INF/lib/byte-buddy-1.17.8.jar'}
    result={'baselineJarSha256':hashlib.sha256(base.read_bytes()).hexdigest(),'candidateJarSha256':hashlib.sha256(cand.read_bytes()).hexdigest(),'baselineLibCount':len(na),'candidateLibCount':len(nb),'baselineOnly':sorted(na-nb),'candidateOnly':sorted(nb-na),'externalLibCount':len(external),'allExternalLibNamesAndHashesEqual':external_equal,'externalLibs':external,'internalLibs':internal,'internalDifferences':differences,'internalClassAndResourceContentEqual':internal_content_equal,'forbiddenTestLibraries':forbidden,'requiredRuntimeLibrariesPresent':required<=nb,'jarMtimeEpoch':int(cand.stat().st_mtime),'startEpoch':int((evidence/'start-epoch.txt').read_text())}
    (evidence/'runtime-lib-comparison.json').write_text(json.dumps(result,indent=2)+'\n')
    if na!=nb or not external_equal or not internal_content_equal or forbidden or not required<=nb:
        raise SystemExit('RUNTIME_DEPENDENCY_PARITY_FAILED')
    for n in nb:
        (libs/pathlib.PurePosixPath(n).name).write_bytes(b.read(n))
print('ALL_EXTERNAL_LIBRARIES_IDENTICAL; INTERNAL_CLASSES_AND_RESOURCES_IDENTICAL')
PY
CLASSPATH="$TARGET/test-classes:$TARGET/classes:$TMPDIR/mockito-runtime-libs/*:/home/ubuntu/.m2/repository/org/mockito/mockito-core/5.17.0/mockito-core-5.17.0.jar:/home/ubuntu/.m2/repository/net/bytebuddy/byte-buddy-agent/1.17.8/byte-buddy-agent-1.17.8.jar"
/usr/bin/time -v -o "$EVIDENCE/mockito-check-time.txt" java -Xmx512m -cp "$CLASSPATH" cn.iocoder.yudao.server.print.store.profile.PrintStoreProfileChecks >"$EVIDENCE/mockito-check.log" 2>&1
python3 - "$EVIDENCE" <<'PY'
import json,pathlib,sys
p=pathlib.Path(sys.argv[1]); lines=(p/'mockito-check.log').read_text().splitlines()
payload=json.loads(next(line for line in reversed(lines) if line.startswith('{')))
assert payload['kind']=='P02_SOURCE_AND_IN_MEMORY_MODEL' and payload['count']>0 and payload['sqlExecuted'] is False and payload['redisExecuted'] is False
(p/'mockito-check-result.json').write_text(json.dumps(payload,indent=2)+'\n')
print('MOCKITO_IN_MEMORY_CHECK_PASS',payload['count'])
PY
