#!/usr/bin/env python3
"""Explicit synthetic-file acceptance on loopback, with scoped fixture cleanup."""
import concurrent.futures, fcntl, hashlib, json, os, pathlib, re, shutil, subprocess, tempfile, time, urllib.error, urllib.request, uuid, zipfile
BASE=pathlib.Path(__file__).resolve().parents[2]
PORT=48081
checks=[];owned={};tokens=[];process=None;logstream=None
class CheckFailure(Exception):pass

def sql(statement):
    result=subprocess.run(['sudo','-n','mysql','-N','-B','print_demo'],input=statement,text=True,capture_output=True)
    if result.returncode:raise CheckFailure('fixture_sql_failed')
    return result.stdout.strip()

def check(name,condition):
    checks.append({'name':name,'passed':bool(condition)})
    if not condition:raise CheckFailure(name)

def api(path,token=None,body=None,method=None,content_type=None,key=None,port=PORT):
    headers={'tenant-id':'1'}
    if token:headers['Authorization']='Bearer '+token
    if key:headers['Idempotency-Key']=key
    if body is not None:
        if not isinstance(body,bytes):body=json.dumps(body).encode();content_type='application/json'
        headers['Content-Type']=content_type or 'application/octet-stream'
    req=urllib.request.Request('http://127.0.0.1:'+str(port)+'/admin-api'+path,data=body,headers=headers,method=method)
    try:
        with urllib.request.urlopen(req,timeout=15) as response:status=response.status;data=response.read();headers=dict(response.headers)
    except urllib.error.HTTPError as response:status=response.code;data=response.read();headers=dict(response.headers)
    if 'json' in headers.get('Content-Type',''):
        data=json.loads(data)
    return status,data,headers

def login(label):
    credentials=json.loads((BASE/'runtime/secrets'/('smoke-'+label+'.json')).read_text())
    _,value,_=api('/system/auth/login',body={k:credentials[k] for k in ('username','password')})
    if value.get('code')!=0:raise CheckFailure('test_login_failed')
    token=value['data']['accessToken'];tokens.append(token);return token

def upload(token,content,key=None,name='fixture.pdf'):
    key=key or str(uuid.uuid4());boundary='printfixtureboundary'
    body=('--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="'+name+'"\r\nContent-Type: application/pdf\r\n\r\n').encode()+content+('\r\n--'+boundary+'--\r\n').encode()
    status,value,headers=api('/print/files',token,body=body,method='POST',content_type='multipart/form-data; boundary='+boundary,key=key)
    if isinstance(value,dict) and value.get('code')==0:
        file_id=value['data']['id']
        if str(uuid.UUID(file_id))!=file_id:raise CheckFailure('invalid_file_identifier')
        owned[file_id]=hashlib.sha256(content).hexdigest()
    return status,value,key

def metadata(token,file_id):return api('/print/files/'+file_id,token)
def settled(token,file_id):
    until=time.monotonic()+20
    while time.monotonic()<until:
        _,value,_=metadata(token,file_id)
        if value.get('code')!=0:raise CheckFailure('metadata_failed')
        if value['data']['status'] in ('READY','FAILED','DELETED'):return value['data']
        time.sleep(.25)
    raise CheckFailure('processing_did_not_settle')

def start(root, capacity_block=False, max_pages=10):
    global process,logstream
    env=dict(os.environ)
    env.update(SERVER_ADDRESS='127.0.0.1',SERVER_PORT=str(PORT),PRINT_FILES_ENABLED='true',
        PRINT_FILES_STORAGE_ROOT=str(root/'storage'),PRINT_FILES_MAX_BYTES=str(2*1024*1024),PRINT_FILES_MAX_PAGES=str(max_pages),
        PRINT_FILES_RETENTION_SECONDS='3600',SPRING_SERVLET_MULTIPART_ENABLED='true',
        SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE='2MB',SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE='3MB',
        SPRING_SERVLET_MULTIPART_LOCATION=str(root/'multipart'),LOGGING_CONFIG='file:'+str(root/'logback.xml'))
    if capacity_block:env['PRINT_FILES_MIN_FREE_BYTES']=str(1<<60)
    logstream=(BASE/'runtime/logs/file-acceptance.log').open('a')
    process=subprocess.Popen(['bash','ops/runtime/start-backend.sh'],cwd=BASE,env=env,stdin=subprocess.DEVNULL,
                             stdout=logstream,stderr=subprocess.STDOUT,start_new_session=True)
    (BASE/'runtime/acceptance/file-test-process.json').write_text(json.dumps({'pid':process.pid,'port':PORT}))
    until=time.monotonic()+55
    while time.monotonic()<until:
        if process.poll() is not None:raise CheckFailure('acceptance_instance_exited')
        try:
            _,value,_=api('/system/auth/get-permission-info')
            if isinstance(value,dict) and value.get('code')==401:return
        except Exception:pass
        time.sleep(1)
    raise CheckFailure('acceptance_instance_start_timeout')

def stop():
    global process,logstream
    if process is not None and process.poll() is None:
        process.terminate()
        try:process.wait(timeout=20)
        except subprocess.TimeoutExpired:process.kill();process.wait(timeout=10)
    if logstream is not None:logstream.close();logstream=None

def fixtures(root):
    libraries=[]
    with zipfile.ZipFile((BASE/'runtime/releases/current/backend.jar').resolve()) as archive:
        for entry in archive.namelist():
            name=pathlib.PurePosixPath(entry).name
            if entry.startswith('BOOT-INF/lib/') and name.startswith(('pdfbox-','fontbox-','commons-logging-')) and name.endswith('.jar'):
                path=root/name;path.write_bytes(archive.read(entry));libraries.append(str(path))
    classes=root/'classes';classes.mkdir();cp=':'.join(libraries)
    source=BASE/'src/backend/yudao-server/src/main/java/cn/iocoder/yudao/server/print/file/PdfInspection.java'
    test=BASE/'src/backend/yudao-server/src/test/java/cn/iocoder/yudao/server/print/file/PdfInspectionChecks.java'
    for command in [
        ['javac','-J-Xmx128m','-cp',cp,'-d',str(classes),str(source),str(test)],
        ['java','-Xmx128m','-cp',str(classes)+':'+cp,'cn.iocoder.yudao.server.print.file.PdfInspectionChecks',str(root)]]:
        if subprocess.run(command,capture_output=True).returncode:raise CheckFailure('fixture_generation_failed')

def run(root):
    fixtures(root)
    (root/'multipart').mkdir(mode=0o700)
    (root/'logback.xml').write_text('<configuration><appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender"><encoder><pattern>%d %-5level %logger{40} - %msg%n</pattern></encoder></appender><logger name="org.apache.pdfbox" level="OFF"/><root level="WARN"><appender-ref ref="CONSOLE"/></root></configuration>')
    start(root)
    admin=login('admin');reader=login('reader');valid=(root/'three.pdf').read_bytes()
    status,value,key=upload(admin,valid,name='../../fixture.pdf')
    check('upload_persisted',status==200 and value.get('code')==0)
    file_id=value['data']['id'];data=settled(admin,file_id)
    check('actual_pdf_page_count',data['status']=='READY' and data['pageCount']==3)
    check('display_name_is_not_a_path',data['displayName']=='fixture.pdf')
    check('metadata_has_no_storage_path',not any(k in data for k in ('storageKey','path','absolutePath','storageRoot')))
    status,content,headers=api('/print/files/'+file_id+'/content',admin)
    check('owner_download_matches_bytes',status==200 and content==valid)
    check('download_private_headers','no-store' in headers.get('Cache-Control','') and headers.get('X-Content-Type-Options')=='nosniff')
    status,value,_=metadata(reader,file_id);check('other_owner_metadata_hidden',status==404 and value.get('code')==1510010004)
    status,value,_=api('/print/files/'+file_id+'/content',reader);check('other_owner_download_hidden',status==404 and value.get('code')==1510010004)
    status,value,_=upload(reader,valid);check('reader_upload_denied',value.get('code')==403)
    _,admin_context,_=api('/print/context',admin)
    _,reader_context,_=api('/print/context',reader)
    admin_id=int(admin_context['data']['userId']);reader_id=int(reader_context['data']['userId'])
    try:
        sql("UPDATE print_file SET owner_user_id="+str(reader_id)+" WHERE id='"+file_id+"' AND content_sha256='"+owned[file_id]+"';")
        status,value,_=api('/print/files/'+file_id,admin,method='DELETE')
        check('write_authorized_other_owner_delete_hidden',status==404 and value.get('code')==1510010004)
    finally:
        sql("UPDATE print_file SET owner_user_id="+str(admin_id)+" WHERE id='"+file_id+"' AND content_sha256='"+owned[file_id]+"';")
    before=sql('SELECT COUNT(*) FROM print_file;')
    status,value,_=upload(admin,b'x'*(2*1024*1024+1))
    check('multipart_oversize_rejected',value.get('code')==400)
    check('oversize_has_no_database_record',sql('SELECT COUNT(*) FROM print_file;')==before)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        repeats=list(pool.map(lambda _:upload(admin,valid,key),range(4)))
    check('concurrent_idempotent_replay',all(r[0]==200 and r[1].get('data',{}).get('id')==file_id for r in repeats))
    status,value,_=upload(admin,(root/'invalid.pdf').read_bytes(),key)
    check('idempotency_content_conflict',status==409 and value.get('code')==1510010005)
    check('one_row_for_idempotent_request',sql("SELECT COUNT(*) FROM print_file WHERE id='"+file_id+"';")=='1')
    for name,expected in [('invalid.pdf','PDF_INVALID'),('encrypted.pdf','PDF_ENCRYPTED')]:
        status,value,_=upload(admin,(root/name).read_bytes());check('accepted_for_async_'+expected,value.get('code')==0)
        failed_id=value['data']['id'];state=settled(admin,failed_id)
        check('rejected_'+expected,state['status']=='FAILED' and state['failureCode']==expected)
        check('failed_blob_removed_'+expected,not (root/'storage/objects'/(failed_id+'.pdf')).exists())
    check('demo_instance_stays_disabled',metadata_on_demo(admin)[1].get('code')==1510010000)
    stop();start(root)
    check('ready_metadata_survives_restart',settled(admin,file_id)['status']=='READY')
    status,content,_=api('/print/files/'+file_id+'/content',admin)
    check('ready_content_survives_restart',content==valid and status==200)
    stop()
    sql("UPDATE print_file SET status='PROCESSING',page_count=NULL WHERE id='"+file_id+"' AND content_sha256='"+owned[file_id]+"';")
    start(root)
    check('seeded_interrupted_processing_recovers',settled(admin,file_id)['status']=='READY')
    sql("UPDATE print_file SET expires_at_ms=1 WHERE id='"+file_id+"' AND content_sha256='"+owned[file_id]+"';")
    _,value,_=metadata(admin,file_id)
    check('expired_file_marked_deleted',value.get('data',{}).get('status')=='DELETED')
    status,value,_=api('/print/files/'+file_id+'/content',admin)
    check('expired_content_denied',status==404)
    check('expired_blob_removed',not (root/'storage/objects'/(file_id+'.pdf')).exists())
    status,value,_=upload(admin,valid);delete_id=value['data']['id'];settled(admin,delete_id)
    status,value,_=api('/print/files/'+delete_id,admin,method='DELETE')
    check('owner_delete_applied',value.get('data',{}).get('status')=='DELETED')
    check('deleted_blob_removed',not (root/'storage/objects'/(delete_id+'.pdf')).exists())
    stop();start(root,capacity_block=True)
    before=sql('SELECT COUNT(*) FROM print_file;')
    status,value,_=upload(admin,valid)
    check('configured_disk_reserve_blocks_upload',status==507 and value.get('code')==1510010008)
    check('capacity_rejection_has_no_record',sql('SELECT COUNT(*) FROM print_file;')==before)

def metadata_on_demo(token):return api('/print/files/unknown',token,port=48080)

def main():
    os.umask(0o077)
    acceptance=BASE/'runtime/acceptance';acceptance.mkdir(exist_ok=True,mode=0o700)
    lock=(BASE/'runtime/locks/fixture-mutation.lock').open('a');fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
    if shutil.disk_usage(BASE).free<10*1024**3:raise CheckFailure('low_disk')
    if sql('SELECT COUNT(*) FROM print_file;')!='0':raise CheckFailure('nonempty_file_table_not_disposable')
    root=pathlib.Path(tempfile.mkdtemp(prefix='file-http-',dir=acceptance))
    failure=None;cleanup=[]
    try:run(root)
    except Exception as error:failure=str(error) if isinstance(error,CheckFailure) else type(error).__name__
    finally:
        for token in tokens:
            try:
                _,value,_=api('/system/auth/logout',token,body={},port=48080)
                if value.get('code')!=0:cleanup.append('session_logout_failed')
            except Exception:cleanup.append('session_logout_failed')
        stop()
        for file_id,digest in owned.items():
            try:sql("DELETE FROM print_file WHERE id='"+file_id+"' AND content_sha256='"+digest+"' AND tenant_id=1;")
            except Exception:cleanup.append('owned_row_cleanup_failed')
        if root.resolve().parent!=acceptance.resolve() or root.is_symlink():cleanup.append('unsafe_scratch_path')
        elif not cleanup:shutil.rmtree(root)
        remaining=int(sql('SELECT COUNT(*) FROM print_file;'))
        if remaining:cleanup.append('file_rows_remain')
        marker=BASE/'runtime/acceptance/file-test-process.json'
        if marker.exists() and process is not None and json.loads(marker.read_text()).get('pid')==process.pid:marker.unlink()
        report={'passed':failure is None and not cleanup,'failure':failure,'checks':checks,'cleanup_errors':cleanup,
                'temporary_files_removed':not root.exists(),'file_rows_remaining':int(sql('SELECT COUNT(*) FROM print_file;')),
                'normal_demo_upload_enabled':False,'test_only_limits':{'bytes':2097152,'pages':10,'retention_seconds':3600},
                'restart_recovery_kind':'actual process restart with explicitly seeded interrupted row; no forced mid-parser crash',
                'hard_parser_timeout_tested':False}
        (BASE/'evidence/acceptance/file-http-smoke.json').write_text(json.dumps(report,indent=2)+'\n')
        print(json.dumps(report))
    return 0 if report['passed'] else 1

if __name__=='__main__':
    raise SystemExit(main())
