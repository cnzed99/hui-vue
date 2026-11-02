#!/usr/bin/env python3
"""Cloud-only standalone source checks, not Maven tests or DB/HTTP/real-device acceptance."""
import argparse, hashlib, json, pathlib, shutil, subprocess, sys, time
parser=argparse.ArgumentParser()
parser.add_argument("--baseline-classes", required=True)
parser.add_argument("--output", required=True)
args=parser.parse_args()
base=pathlib.Path(__file__).resolve().parents[2]
output=pathlib.Path(args.output).resolve()
if not output.is_relative_to(base/"target"):
    raise SystemExit("Output must be under this worktree target")
output.mkdir(parents=True,exist_ok=True)
classes=output/"classes"
if classes.exists(): shutil.rmtree(classes)
classes.mkdir()
baseline=pathlib.Path(args.baseline_classes).resolve()
dirs=sorted(baseline.glob("**/target/classes"))
jars=sorted(pathlib.Path("/home/ubuntu/.m2/repository").rglob("*.jar"))
classpath=[classes]+dirs+jars
changed=subprocess.check_output(["git","diff","14604813b6baeee914b35110f1a5358859bfd0b7","--name-only"],cwd=base,text=True).splitlines()
sources=[base/p for p in changed if p.endswith(".java") and "/src/main/" in p]
testroot=base/"yudao-server/src/test/java/cn/iocoder/yudao/server/print/miniappdemo"
sources+=list(testroot.glob("imagecollage/MiniappDemoCollage*Checks.java"))
sources+=list(testroot.glob("file/CollageRealLockFixture.java"))
sources=sorted(set(sources))
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
manifest={"kind":"STANDALONE_SOURCE_AND_REAL_OS_LOCK_CHECKS","startedAtEpoch":time.time(),
 "sourceCommit":subprocess.check_output(["git","rev-parse","HEAD"],cwd=base,text=True).strip(),
 "sourceStatus":subprocess.check_output(["git","status","--porcelain"],cwd=base,text=True).splitlines(),
 "sourceFiles":{str(p.relative_to(base)):sha(p) for p in sources},
 "baselineClassesRoot":str(baseline),"classpath":[str(p) for p in classpath],
 "classpathDigests":{str(p):sha(p) for p in jars},
 "baselineClassDigests":{str(p):sha(p) for d in dirs for p in sorted(d.rglob("*.class"))},
 "runs":[],"boundaries":["No Maven test execution","Persistence and renderer stubbed in state harness",
 "Real production file-service OS lock used across independent JVMs; persistence only stubbed",
 "No HTTP, database, original-system, or WeChat device acceptance"]}
def run(name,command):
    result=subprocess.run(command,cwd=base,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    log=output/(name+".log");log.write_text(result.stdout)
    manifest["runs"].append({"name":name,"command":command,"exitCode":result.returncode,"log":str(log),"sha256":sha(log)})
    print(result.stdout,end="")
    if result.returncode:raise RuntimeError(name+" failed")
try:
    cp=":".join(map(str,classpath))
    run("javac",["javac","-J-Xmx256m","-proc:none","-encoding","UTF-8","-cp",cp,"-d",str(classes)]+list(map(str,sources)))
    run("state",["java","-Xmx128m","-cp",cp,"cn.iocoder.yudao.server.print.miniappdemo.imagecollage.MiniappDemoCollageUploadChecks"])
    fixture=output/"real-lock-fixture"
    if fixture.exists():raise RuntimeError("Refusing existing lock fixture")
    run("real-lock",["java","-Xmx128m","-cp",cp,"cn.iocoder.yudao.server.print.miniappdemo.imagecollage.MiniappDemoCollageRealLockChecks","owner",str(fixture)])
    shutil.rmtree(fixture)
    manifest["fixtureRemovedAfterJvmExit"]=True
    manifest["result"]="PASS"
except Exception as e:
    manifest["result"]="FAIL"
    manifest["failureType"]=type(e).__name__
finally:
    manifest["finishedAtEpoch"]=time.time()
    (output/"manifest.json").write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+"\n")
print("STAGED_EVIDENCE_RESULT",manifest["result"],str(output/"manifest.json"))
sys.exit(0 if manifest["result"]=="PASS" else 1)
