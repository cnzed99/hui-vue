#!/usr/bin/env python3
"""Independent quote acceptance against real synthetic PDFs and immutable price versions."""
import concurrent.futures,decimal,fcntl,json,os,pathlib,shutil,subprocess,tempfile,time,uuid
import file_http_smoke as f
BASE=f.BASE
checks=[];profiles=[];other_store=None;quote_ids=set();cleanup=[]
class QuoteFailure(Exception):pass

def check(name,condition):
    checks.append({'name':name,'passed':bool(condition)})
    if not condition:raise QuoteFailure(name)

def pdf(pages,tag):
    objects=[b'<< /Type /Catalog /Pages 2 0 R >>', ('<< /Type /Pages /Kids ['+' '.join(str(i+3)+' 0 R' for i in range(pages))+'] /Count '+str(pages)+' >>').encode()]
    for _ in range(pages):objects.append(b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << >> >>')
    data=b'%PDF-1.4\n%'+tag.encode()+b'\n';offsets=[0]
    for number,obj in enumerate(objects,1):
        offsets.append(len(data));data+=str(number).encode()+b' 0 obj\n'+obj+b'\nendobj\n'
    xref=len(data);data+=('xref\n0 '+str(len(objects)+1)+'\n0000000000 65535 f \n').encode()
    for offset in offsets[1:]:data+=(str(offset).zfill(10)+' 00000 n \n').encode()
    return data+('trailer\n<< /Size '+str(len(objects)+1)+' /Root 1 0 R >>\nstartxref\n'+str(xref)+'\n%%EOF\n').encode()

def profile(store=1,version='v1',simplex='0.20',duplex='0.20',tier=None):
    identifier=str(uuid.uuid4());profiles.append(identifier)
    tier_value='NULL' if tier is None else "CAST('"+json.dumps(tier,separators=(',',':'))+"' AS JSON)"
    f.sql("INSERT INTO print_price_profile(id,tenant_id,store_id,profile_key,version,mode,currency,simplex_price_per_sheet,duplex_price_per_sheet,tier_json,created_at_ms) VALUES('"+identifier+"',1,"+str(store)+",'"+marker+"','"+version+"','DEMO','CNY','"+simplex+"','"+duplex+"',"+tier_value+",1);")
    return identifier

def quote(token,file_id,profile_id,copies=1,side='SIMPLEX',key=None,extra=None):
    key=key or str(uuid.uuid4());body={'fileId':file_id,'priceProfileId':profile_id,'copies':copies,'side':side}
    if extra:body.update(extra)
    status,value,_=f.api('/print/quotes',token,body=body,key=key)
    if isinstance(value,dict) and value.get('code')==0:quote_ids.add(value['data']['id'])
    return status,value,key

def snapshot(result):
    status,value,_=result
    if status!=200 or value.get('code')!=0:raise QuoteFailure('unexpected_quote_failure')
    return value['data']['snapshot']

def check_amount(name,result,expected,sheets):
    data=snapshot(result)
    check(name,isinstance(data['totalAmount'],str) and decimal.Decimal(data['totalAmount'])==decimal.Decimal(expected)
          and data['totalSheets']==str(sheets))
    return data

def ready_file(token,pages,label):
    status,value,_=f.upload(token,pdf(pages,label))
    if status!=200 or value.get('code')!=0:raise QuoteFailure('fixture_upload_failed')
    identifier=value['data']['id'];data=f.settled(token,identifier)
    check('parsed_fixture_'+label,data['status']=='READY' and data['pageCount']==pages)
    return identifier

def forbidden_update(statement):
    result=subprocess.run(['sudo','-n','mysql','print_demo'],input=statement,text=True,capture_output=True)
    return result.returncode!=0 and '1644 (45000)' in result.stderr and 'immutable' in result.stderr

def run(root):
    global other_store
    (root/'multipart').mkdir(mode=0o700)
    (root/'logback.xml').write_text('<configuration><root level="OFF"/></configuration>')
    os.environ.update(PRINT_QUOTES_ENABLED='true',PRINT_QUOTES_TTL_SECONDS='6')
    f.start(root,max_pages=20)
    admin=f.login('admin');reader=f.login('reader')
    three=ready_file(admin,3,'three');six=ready_file(admin,6,'six-a');six_b=ready_file(admin,6,'six-b');eleven=ready_file(admin,11,'eleven')
    plain=profile()
    first=quote(admin,three,plain,2,'DUPLEX');first_id=first[1]['data']['id']
    old=check_amount('duplex_copy_rounding_080',first,'0.80',4)
    check('snapshot_uses_actual_file_pages',old['pagesPerCopy']==3 and old['aggregationPolicy']=='PER_FILE_ALL_COPIES')
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        repeated=list(pool.map(lambda _:quote(admin,three,plain,2,'DUPLEX',first[2]),range(4)))
    check('concurrent_quote_replay_same_id',all(r[0]==200 and r[1]['data']['id']==first_id and r[1]['data']['snapshot']==old for r in repeated))
    fresh_key=str(uuid.uuid4())
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        created=list(pool.map(lambda _:quote(admin,three,plain,2,'DUPLEX',fresh_key),range(4)))
    check('concurrent_first_creation_single_quote',all(r[0]==200 and r[1].get('code')==0 for r in created) and len({r[1]['data']['id'] for r in created})==1)
    key_digest=__import__('hashlib').sha256(fresh_key.encode()).hexdigest()
    check('concurrent_creation_single_database_row',f.sql("SELECT COUNT(*) FROM print_quote WHERE tenant_id=1 AND idempotency_sha256='"+key_digest+"';")=='1')
    status,value,_=f.api('/print/quotes/'+first_id,admin,port=48080)
    check('normal_demo_quote_feature_stays_disabled',status==503 and value.get('code')==1510020000)
    status,value,_=quote(admin,three,plain,3,'DUPLEX',first[2])
    check('different_input_same_key_rejected',status==409 and value.get('code')==1510020006)
    before=f.sql('SELECT COUNT(*) FROM print_quote;')
    status,value,_=quote(admin,three,plain,extra={'totalAmount':'0.00','pagesPerCopy':999})
    check('client_price_and_page_tampering_rejected',status==400 and value.get('code')==1510020001)
    check('invalid_request_creates_no_quote',f.sql('SELECT COUNT(*) FROM print_quote;')==before)
    status,value,_=quote(reader,three,plain)
    check('reader_quote_create_denied',value.get('code')==403)
    status,value,_=f.api('/print/quotes/'+first_id,reader)
    check('other_owner_quote_not_visible',status==404 and value.get('code')==1510020008)
    tier={'strictlyMoreThanSheets':10,'simplexPricePerSheet':'0.15','duplexPricePerSheet':'0.15'}
    tiered=profile(version='tier-v1',tier=tier)
    five=ready_file(admin,5,'five')
    boundary=check_amount('exact_threshold_stays_base',quote(admin,five,tiered,2),'2.00',10)
    check('strict_threshold_not_greater_or_equal',boundary['tierApplied'] is False)
    tier_quote=quote(admin,eleven,tiered)
    tier_snap=check_amount('eleven_sheets_all_at_tier_165',tier_quote,'1.65',11)
    check('tier_snapshot_records_decision',tier_snap['tierApplied'] is True and tier_snap['appliedTierThreshold']=='10' and tier_snap['tier']=={'strictlyMoreThanSheets':'10','simplexPricePerSheet':'0.15','duplexPricePerSheet':'0.15'})
    one=check_amount('six_file_a_base',quote(admin,six,tiered),'1.20',6)
    two=check_amount('six_file_b_base',quote(admin,six_b,tiered),'1.20',6)
    check('separate_files_do_not_merge_tiers',decimal.Decimal(one['totalAmount'])+decimal.Decimal(two['totalAmount'])==decimal.Decimal('2.40') and not one['tierApplied'] and not two['tierApplied'])
    check_amount('copies_count_in_file_tier_180',quote(admin,six,tiered,2),'1.80',12)
    newer=profile(version='v2',simplex='0.30',duplex='0.30')
    check_amount('new_profile_version_used',quote(admin,three,newer,2,'DUPLEX'),'1.20',4)
    _,old_read,_=f.api('/print/quotes/'+first_id,admin)
    check('old_snapshot_unchanged_after_new_price',old_read['data']['snapshot']==old)
    check('price_profile_update_blocked',forbidden_update("UPDATE print_price_profile SET simplex_price_per_sheet='99' WHERE id='"+plain+"';"))
    check('quote_snapshot_update_blocked',forbidden_update("UPDATE print_quote SET snapshot_json=JSON_SET(snapshot_json,'$.totalAmount','0') WHERE id='"+first_id+"';"))
    other_store=int(f.sql("INSERT INTO print_store(tenant_id,store_code,name,status,creator,updater) VALUES(1,'"+marker+"','Quote fixture store',0,'"+marker+"','"+marker+"'); SELECT LAST_INSERT_ID();"))
    other_profile=profile(store=other_store,version='other-store')
    status,value,_=quote(admin,three,other_profile)
    check('other_store_price_profile_hidden',status==404 and value.get('code')==1510020002)
    bad_tier=profile(version='unsupported-multiple-tiers',tier=[tier,tier])
    status,value,_=quote(admin,three,bad_tier)
    check('unconfirmed_multiple_tiers_rejected',status==409 and value.get('code')==1510020003)
    status,value,_=quote(admin,str(uuid.uuid4()),plain)
    check('unknown_file_rejected',status==404 and value.get('code')==1510020004)
    failed_status,failed,_=f.upload(admin,b'not a PDF');failed_id=failed['data']['id'];f.settled(admin,failed_id)
    status,value,_=quote(admin,failed_id,plain)
    check('failed_file_cannot_quote',status==409 and value.get('code')==1510020005)
    for bad_copies in [0,-1,1.5,'2',9223372036854775808,9223372036854775807]:
        status,value,_=quote(admin,three,plain,bad_copies)
        check('reject_invalid_or_overflow_copies_'+str(bad_copies),status==400 and value.get('code')==1510020001)
    _,reader_context,_=f.api('/print/context',reader)
    owner=int(f.sql("SELECT owner_user_id FROM print_file WHERE id='"+five+"';"))
    try:
        f.sql("UPDATE print_file SET owner_user_id="+str(reader_context['data']['userId'])+" WHERE id='"+five+"';")
        status,value,_=quote(admin,five,plain)
        check('write_authorized_other_owner_file_hidden',status==404 and value.get('code')==1510020004)
    finally:f.sql("UPDATE print_file SET owner_user_id="+str(owner)+" WHERE id='"+five+"';")
    expired=ready_file(admin,1,'expired')
    f.sql("UPDATE print_file SET expires_at_ms=1 WHERE id='"+expired+"';")
    status,value,_=quote(admin,expired,plain)
    check('expired_file_cannot_quote',status==409 and value.get('code')==1510020005)
    deleted=ready_file(admin,1,'deleted')
    f.api('/print/files/'+deleted,admin,method='DELETE')
    status,value,_=quote(admin,deleted,plain)
    check('deleted_file_cannot_quote',status==409 and value.get('code')==1510020005)
    f.stop();f.start(root,max_pages=20)
    _,reloaded,_=f.api('/print/quotes/'+first_id,admin)
    check('snapshot_survives_process_restart',reloaded['data']['snapshot']==old)
    check('expired_quote_is_labelled',reloaded['data']['status']=='EXPIRED')
    status,value,_=quote(admin,three,plain,2,'DUPLEX',first[2])
    check('expired_idempotency_key_not_renewed',status==409 and value.get('code')==1510020007)
    _,still_old,_=f.api('/print/quotes/'+first_id,admin)
    check('expired_snapshot_and_expiry_unchanged',still_old['data']['snapshot']==old)
    fresh=quote(admin,three,plain,2,'DUPLEX')
    check('explicit_new_quote_has_new_identifier',fresh[1]['data']['id']!=first_id and fresh[1]['data']['status']=='VALID')

def main():
    os.umask(0o077)
    acceptance=BASE/'runtime/acceptance';acceptance.mkdir(exist_ok=True,mode=0o700)
    with (BASE/'runtime/locks/fixture-mutation.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        if f.sql('SELECT COUNT(*) FROM print_file;')!='0':raise QuoteFailure('file_table_not_empty')
        root=pathlib.Path(tempfile.mkdtemp(prefix='quote-http-',dir=acceptance));failure=None
        try:run(root)
        except Exception as error:failure=str(error) if isinstance(error,(QuoteFailure,f.CheckFailure)) else type(error).__name__
        finally:
            for token in f.tokens:
                try:
                    _,value,_=f.api('/system/auth/logout',token,body={},port=48080)
                    if value.get('code')!=0:cleanup.append('logout_failed')
                except Exception:cleanup.append('logout_failed')
            f.stop()
            for identifier in profiles:
                try:
                    f.sql("DELETE FROM print_quote WHERE price_profile_id='"+identifier+"' AND tenant_id=1;")
                    f.sql("DELETE FROM print_price_profile WHERE id='"+identifier+"' AND profile_key='"+marker+"';")
                except Exception:cleanup.append('quote_fixture_cleanup_failed')
            for identifier,digest in f.owned.items():
                try:f.sql("DELETE FROM print_file WHERE id='"+identifier+"' AND content_sha256='"+digest+"' AND tenant_id=1;")
                except Exception:cleanup.append('file_fixture_cleanup_failed')
            if other_store is not None:
                try:f.sql("DELETE FROM print_store WHERE id="+str(other_store)+" AND creator='"+marker+"';")
                except Exception:cleanup.append('store_fixture_cleanup_failed')
            if root.resolve().parent==acceptance.resolve() and not root.is_symlink() and not cleanup:shutil.rmtree(root)
            descriptor=BASE/'runtime/acceptance/file-test-process.json'
            if descriptor.exists() and f.process is not None and f.process.poll() is not None:
                if json.loads(descriptor.read_text()).get('pid')==f.process.pid:descriptor.unlink()
            remaining=int(f.sql('SELECT COUNT(*) FROM print_file;'))
            if remaining:cleanup.append('file_rows_remaining')
            result={'file_rows_remaining':remaining,'temporary_instance_stopped':f.process is None or f.process.poll() is not None,
                    'passed':failure is None and not cleanup,'failure':failure,'checks':checks,'cleanup_errors':cleanup,
                    'temporary_files_removed':not root.exists(),'test_only_quote_ttl_seconds':6,
                    'business_prices_seeded':False,'normal_demo_features_enabled':False,'test_only_max_pages':20}
            (BASE/'evidence/acceptance/quote-http-smoke.json').write_text(json.dumps(result,indent=2)+'\n')
            print(json.dumps(result))
        return 0 if result['passed'] else 1

marker='quote-fixture-'+uuid.uuid4().hex[:16]
if __name__=='__main__':raise SystemExit(main())
