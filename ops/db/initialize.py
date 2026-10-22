#!/usr/bin/env python3
"""Create an isolated empty-schema demo database. Never imports upstream records."""
import crypt, hashlib, json, os, pathlib, re, secrets, shlex, subprocess, sys
BASE=pathlib.Path('/home/ubuntu/print-system')
os.umask(0o077)
def sql(text, database=None):
 args=['sudo','-n','mysql','--default-character-set=utf8mb4','-N','-B']
 if database: args.append(database)
 p=subprocess.run(args,input=text,text=True,capture_output=True)
 if p.returncode: raise RuntimeError('mysql_operation_failed_no_secret_output')
 return p.stdout
if sql("SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME='print_demo';").strip():
 raise RuntimeError('database_already_exists_stop_without_changes')
secret_path=BASE/'runtime/secrets/runtime.env'
if secret_path.exists(): raise RuntimeError('secrets_already_exist_stop_without_changes')
original=(BASE/'src/backend/sql/mysql/ruoyi-vue-pro.sql').read_text()
blocks=[]; names=[]
for m in re.finditer(r'CREATE TABLE `([^`]+)`\s+\([\s\S]*?\) ENGINE[^;]+;',original):
 name=m.group(1)
 if not name.startswith(('system_','infra_')): continue
 blocks.append(re.sub(r'AUTO_INCREMENT\s*=\s*\d+', 'AUTO_INCREMENT=1', m.group()))
 names.append(name)
assert len(names)==43 and len(set(names))==43
schema='SET NAMES utf8mb4;\n'+'\n\n'.join(blocks)+'\n'
assert 'DROP TABLE' not in schema and 'INSERT INTO' not in schema
(BASE/'ops/db/schema.sql').write_text(schema)
env={
 'PRINT_DB_PASSWORD':secrets.token_hex(24),
 'PRINT_REDIS_PASSWORD':secrets.token_hex(24),
 'PRINT_SECURITY_SECRET':secrets.token_hex(24),
 'PRINT_DB_ENCRYPTION_KEY':secrets.token_hex(16),
 'PRINT_RUNTIME_DIR':str(BASE/'runtime'),
}
secret_path.write_text(''.join(k+'='+shlex.quote(v)+'\n' for k,v in env.items()))
secret_path.chmod(0o600)
accounts=[{'username':'printadmin','role':'tenant_admin','status':0}, {'username':'printreader','role':'print_reader','status':0}, {'username':'printdisabled','role':'print_reader','status':1}]
for a in accounts: a['password']=secrets.token_urlsafe(12); assert len(a['password'])==16
auth={'tenant_id':1,'tenant_name':'Print Demo','accounts':accounts}
auth_path=BASE/'runtime/secrets/demo-accounts.json'; auth_path.write_text(json.dumps(auth,indent=2)); auth_path.chmod(0o600)
sql("CREATE DATABASE print_demo CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;")
sql(schema,'print_demo')
sql("CREATE USER 'print_app'@'127.0.0.1' IDENTIFIED BY '"+env['PRINT_DB_PASSWORD']+"'; GRANT SELECT,INSERT,UPDATE,DELETE ON print_demo.* TO 'print_app'@'127.0.0.1';")
client=BASE/'runtime/secrets/mysql-app.cnf'
client.write_text('[client]\nhost=127.0.0.1\nport=3306\nuser=print_app\npassword='+env['PRINT_DB_PASSWORD']+'\ndatabase=print_demo\n'); client.chmod(0o600)
rows=[
 "INSERT INTO system_tenant_package(id,name,status,menu_ids) VALUES(1,'Print Demo Package',0,'[1000,1001,1002,1003]');",
 "INSERT INTO system_tenant(id,name,contact_user_id,contact_name,status,websites,package_id,expire_time,account_count) VALUES(1,'Print Demo',1,'Demo Administrator',0,'[]',1,'2099-12-31 23:59:59',10);",
 "INSERT INTO system_dept(id,name,parent_id,sort,status,tenant_id) VALUES(1,'Demo Store',0,0,0,1);",
 "INSERT INTO system_role(id,name,code,sort,status,type,data_scope,data_scope_dept_ids,tenant_id) VALUES(1,'Store Administrator','tenant_admin',0,0,1,1,'[]',1),(2,'Restricted Reader','print_reader',1,0,2,5,'[]',1);",
 "INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,icon,component,component_name) VALUES(1000,'Administration','',1,0,0,'/system','ep:setting',NULL,NULL),(1001,'Users','',2,0,1000,'user','ep:user','system/user/index','SystemUser'),(1002,'List users','system:user:list',3,0,1001,'','',NULL,NULL),(1003,'View users','system:user:query',3,1,1001,'','',NULL,NULL);",
 "INSERT INTO system_role_menu(role_id,menu_id,tenant_id) VALUES(1,1000,1),(1,1001,1),(1,1002,1),(1,1003,1);",
]
for n,a in enumerate(accounts,1):
 password_hash=crypt.crypt(a['password'],crypt.mksalt(crypt.METHOD_BLOWFISH,rounds=1024))
 assert password_hash.startswith(('$2b$10$','$2a$10$')) and crypt.crypt(a['password'],password_hash)==password_hash
 rows.append("INSERT INTO system_users(id,username,password,nickname,dept_id,post_ids,status,tenant_id) VALUES(%d,'%s','%s','%s',1,'[]',%d,1);"%(n,a['username'],password_hash,a['username'],a['status']))
 rows.append("INSERT INTO system_user_role(user_id,role_id,tenant_id) VALUES(%d,%d,1);"%(n,1 if n==1 else 2))
rows.append("INSERT INTO system_oauth2_client(id,client_id,secret,name,logo,status,access_token_validity_seconds,refresh_token_validity_seconds,redirect_uris,authorized_grant_types,scopes,auto_approve_scopes,authorities,resource_ids) VALUES(1,'default','%s','Print Demo Admin','',0,1800,86400,'[]','[\"password\",\"refresh_token\"]','[]','[]','[]','[]');"%secrets.token_hex(24))
sql('START TRANSACTION;\n'+'\n'.join(rows)+'\nCOMMIT;','print_demo')
# No seed SQL containing hashes or secrets is persisted; original upstream records never run.
report={'schema_tables':names,'table_count':len(names),'schema_sha256':hashlib.sha256(schema.encode()).hexdigest(),'source_sha256':hashlib.sha256(original.encode()).hexdigest(),'upstream_records_imported':False,'demo_tenants':1,'accounts':len(accounts),'admin_role':'tenant_admin','super_admin_created':False,'password_length':16,'bcrypt_cost':10,'secret_file_mode':oct(secret_path.stat().st_mode&0o777),'backend_started':False}
(BASE/'evidence/database/initialization.json').write_text(json.dumps(report,indent=2))
print(json.dumps({k:v for k,v in report.items() if k!='schema_tables'}))
