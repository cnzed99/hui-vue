#!/usr/bin/env python3
import json,os,pathlib,shlex,subprocess
BASE=pathlib.Path('/home/ubuntu/print-system')
os.umask(0o077)
env={}
for line in (BASE/'runtime/secrets/runtime.env').read_text().splitlines():
 k,v=line.split('=',1); env[k]=shlex.split(v)[0]
def run(args,**kwargs):
 p=subprocess.run(args,capture_output=True,text=True,**kwargs)
 if p.returncode: raise RuntimeError('service_configuration_failed_no_secret_output')
 return p.stdout
mysql_conf=BASE/'ops/runtime/mysql.cnf'
mysql_conf.write_text('[mysqld]\nbind-address=127.0.0.1\nmysqlx-bind-address=127.0.0.1\ninnodb_buffer_pool_size=128M\nmax_connections=40\nperformance_schema=OFF\nslow_query_log=OFF\n')
run(['sudo','-n','install','-m','644',str(mysql_conf),'/etc/mysql/mysql.conf.d/zz-print-system.cnf'])
run(['sudo','-n','mysqld','--validate-config'])
run(['sudo','-n','systemctl','restart','mysql'])
redis_conf=BASE/'runtime/secrets/redis-private.conf'
redis_conf.write_text('bind 127.0.0.1 ::1\nprotected-mode yes\nrequirepass '+env['PRINT_REDIS_PASSWORD']+'\nmaxmemory 128mb\nmaxmemory-policy noeviction\nappendonly yes\nauto-aof-rewrite-percentage 100\nauto-aof-rewrite-min-size 64mb\n')
run(['sudo','-n','install','-m','640','-o','root','-g','redis',str(redis_conf),'/etc/redis/print-system.conf'])
p=run(['sudo','-n','cat','/etc/redis/redis.conf'])
if 'include /etc/redis/print-system.conf' not in p:
 run(['sudo','-n','tee','-a','/etc/redis/redis.conf'],input='\ninclude /etc/redis/print-system.conf\n')
run(['sudo','-n','systemctl','restart','redis-server'])
redis_conf.unlink()
redis_env=dict(os.environ,REDISCLI_AUTH=env['PRINT_REDIS_PASSWORD'])
assert run(['redis-cli','--no-auth-warning','PING'],env=redis_env).strip()=='PONG'
unauth=run(['redis-cli','PING'])
assert 'NOAUTH' in unauth
client=['mysql','--defaults-extra-file='+str(BASE/'runtime/secrets/mysql-app.cnf'),'-N','-B']
assert run(client,input='SELECT COUNT(*) FROM system_tenant;').strip()=='1'
# Actual DML transaction roundtrip, rollback leaves no additional business record.
assert run(client,input="START TRANSACTION; INSERT INTO infra_config(category,type,name,config_key,value,visible) VALUES('verification',2,'roundtrip','print.verification.roundtrip','ok',b'0'); SELECT value FROM infra_config WHERE config_key='print.verification.roundtrip'; ROLLBACK;").strip()=='ok'
assert run(client,input="SELECT COUNT(*) FROM infra_config WHERE config_key='print.verification.roundtrip';").strip()=='0'
for forbidden in ['CREATE TABLE print_demo.should_not_exist(id INT);','SELECT COUNT(*) FROM mysql.user;']:
 p=subprocess.run(client,input=forbidden,text=True,capture_output=True)
 assert p.returncode!=0
report={'mysql_loopback_only':True,'redis_loopback_only':True,'mysql_runtime_dml_roundtrip':True,'mysql_runtime_ddl_denied':True,'mysql_system_table_denied':True,'redis_authenticated_ping':True,'redis_unauthenticated_denied':True,'mysql_buffer_pool_mb':128,'redis_maxmemory_mb':128,'backend_started':False}
(BASE/'evidence/database/service-checks.json').write_text(json.dumps(report,indent=2)); print(json.dumps(report))
