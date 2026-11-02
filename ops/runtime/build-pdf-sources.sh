#!/usr/bin/env bash
set -Eeuo pipefail
ROOT=/home/ubuntu/print-system
MINI=$ROOT/runtime/worktrees/pdf-source-routing-miniapp-20261102
BACK=$ROOT/runtime/worktrees/pdf-catalog-backend-20261102
[[ $# == 1 && $1 =~ ^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$ ]] || exit 64
OUT=$ROOT/evidence/deploy/pdf-sources-$1
mkdir -p "$OUT"
[[ $(git -C "$MINI" rev-parse HEAD) == 27abcbe945ceb567a315f961d8bb50bd3bb2dc87 ]]
[[ $(git -C "$BACK" rev-parse HEAD) == 7efd316fb154c65fff089da7a5919284fb1f8cd6 ]]
git -C "$MINI" diff --quiet; git -C "$BACK" diff --quiet
export PATH="$ROOT/runtime/node/current/bin:$ROOT/runtime/npm/bin:/usr/bin:/bin"
export MAVEN_OPTS='-Xmx768m -XX:MaxMetaspaceSize=256m'
export NODE_OPTIONS=--max-old-space-size=1536
(cd "$BACK" && mvn -B -ntp -pl yudao-server -am -Dmaven.test.skip=true package) >"$OUT/backend-package.log" 2>&1
(cd "$MINI" && npm run type-check && npm run check:catalog && npm run check:document-sources && npm run build:h5) >"$OUT/miniapp-build.log" 2>&1
python3 - "$OUT/build-manifest.json" "$BACK/yudao-server/target/yudao-server.jar" "$MINI/dist/build/h5/index.html" <<'PY'
import datetime,hashlib,json,pathlib,sys
out,jar,index=map(pathlib.Path,sys.argv[1:])
def artifact(p): return {'path':str(p),'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'bytes':p.stat().st_size}
x={'status':'PASS','finishedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
   'backendCommit':'7efd316fb154c65fff089da7a5919284fb1f8cd6','miniappCommit':'27abcbe945ceb567a315f961d8bb50bd3bb2dc87',
   'backendPackage':'BUILD_SUCCESS','mavenTests':'SKIPPED','typeCheck':'PASS','sourceDirectedChecks':'PASS','h5Build':'PASS',
   'backendJar':artifact(jar),'h5Index':artifact(index),'wechatDevice':'NOT_RUN','deployment':'NOT_RUN'}
out.write_text(json.dumps(x,indent=2)+'\n')
PY
printf 'PDF_SOURCES_FULL_BUILD_PASS\n'
