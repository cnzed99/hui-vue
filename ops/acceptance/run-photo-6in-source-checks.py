#!/usr/bin/env python3
"""Cloud-only bounded javac plus real renderer checks. No full build or service changes."""
import pathlib,subprocess,json,time,hashlib
base=pathlib.Path(__file__).resolve().parents[2]
output=base/'target/photo-6in-checks';output.mkdir(parents=True,exist_ok=True)
baseline=pathlib.Path('/home/ubuntu/print-system/runtime/worktrees/id-photo-preview-backend-20261103')
cp=[output/'classes']+list(baseline.glob('**/target/classes'))+list(pathlib.Path('/home/ubuntu/.m2/repository').rglob('*.jar'))
classpath=':'.join(map(str,cp));(output/'classpath.txt').write_text(classpath)
sources=list((base/'yudao-server/src/main/java/cn/iocoder/yudao/server/print').rglob('*.java'))
sources+=list((base/'yudao-server/src/test/java/cn/iocoder/yudao/server/print/miniappdemo/photo').glob('*Checks.java'))
manifest={'kind':'BOUNDED_SOURCE_RENDERER_CHECKS','startedAt':time.time(),'runs':[],'sourceFiles':{str(p.relative_to(base)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},'boundaries':['No Maven build','No production writes or service changes','No external original-system/WeChat/real-device acceptance']}
def run(name,args):
 p=subprocess.run(args,cwd=base,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT);(output/(name+'.log')).write_text(p.stdout);print(p.stdout,end='');manifest['runs'].append({'name':name,'exit':p.returncode});return p.returncode
result=run('compile',['javac','-J-Xmx256m','-proc:none','-encoding','UTF-8','-cp',classpath,'-d',str(output/'classes')]+list(map(str,sources)))
if result==0:result=run('contracts-renderer',['java','-Xmx128m','-cp',classpath,'cn.iocoder.yudao.server.print.miniappdemo.photo.MiniappDemoPhotoChecks',str(output/'render')])
manifest['result']='PASS' if result==0 else 'FAIL';manifest['endedAt']=time.time();(output/'source-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n');raise SystemExit(result)
