-- Incremental print staff/store foundation. Run with migrate_print_foundation.py.
-- Existing data is never dropped, reset or replaced. Active status is 0.
CREATE TABLE IF NOT EXISTS print_store (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  store_code VARCHAR(64) NOT NULL,
  name VARCHAR(128) NOT NULL,
  status TINYINT NOT NULL DEFAULT 0,
  creator VARCHAR(64) NOT NULL DEFAULT '',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updater VARCHAR(64) NOT NULL DEFAULT '',
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted BIT(1) NOT NULL DEFAULT b'0',
  PRIMARY KEY (id),
  UNIQUE KEY uk_print_store_tenant_code (tenant_id, store_code),
  UNIQUE KEY uk_print_store_tenant_id (tenant_id, id),
  KEY idx_print_store_tenant_status (tenant_id, status, deleted),
  CONSTRAINT ck_print_store_status CHECK (status IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS print_staff_store (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  status TINYINT NOT NULL DEFAULT 0,
  creator VARCHAR(64) NOT NULL DEFAULT '',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updater VARCHAR(64) NOT NULL DEFAULT '',
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted BIT(1) NOT NULL DEFAULT b'0',
  PRIMARY KEY (id),
  UNIQUE KEY uk_print_staff_store (tenant_id, store_id, user_id),
  KEY idx_print_staff_scope (tenant_id, user_id, status, deleted),
  CONSTRAINT fk_print_staff_store_tenant FOREIGN KEY (tenant_id, store_id)
    REFERENCES print_store (tenant_id, id),
  CONSTRAINT ck_print_staff_store_status CHECK (status IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

START TRANSACTION;
INSERT INTO print_store (id,tenant_id,store_code,name,status,creator,updater)
SELECT 1,1,'DEMO001','演示门店',0,'foundation-v1','foundation-v1'
WHERE NOT EXISTS (SELECT 1 FROM print_store WHERE id=1);

INSERT INTO print_staff_store (tenant_id,store_id,user_id,status,creator,updater)
SELECT 1,1,u.id,0,'foundation-v1','foundation-v1'
FROM system_users u WHERE u.tenant_id=1 AND u.username IN ('printadmin','printreader') AND u.deleted=b'0'
AND NOT EXISTS (SELECT 1 FROM print_staff_store m WHERE m.tenant_id=1 AND m.store_id=1 AND m.user_id=u.id);

INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,status,visible,keep_alive,always_show)
SELECT 2000,'打印工作台','',1,1,0,'/print','ep:printer',0,b'1',b'1',b'1'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=2000);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,status,visible)
SELECT 2001,'查询打印文件','print:file:query',3,1,2000,'',0,b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=2001);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,status,visible)
SELECT 2002,'上传打印文件','print:file:upload',3,2,2000,'',0,b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=2002);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,status,visible)
SELECT 2003,'删除打印文件','print:file:delete',3,3,2000,'',0,b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=2003);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,status,visible)
SELECT 2004,'生成打印报价','print:quote:create',3,4,2000,'',0,b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=2004);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,status,visible)
SELECT 2005,'查询打印报价','print:quote:query',3,5,2000,'',0,b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=2005);

INSERT INTO system_role_menu (role_id,menu_id,tenant_id,creator,updater)
SELECT r.id,m.id,1,'foundation-v1','foundation-v1'
FROM system_role r JOIN system_menu m ON m.id BETWEEN 2000 AND 2005
WHERE r.tenant_id=1 AND r.code='tenant_admin' AND r.deleted=b'0'
AND NOT EXISTS (SELECT 1 FROM system_role_menu rm WHERE rm.role_id=r.id AND rm.menu_id=m.id AND rm.tenant_id=1);
INSERT INTO system_role_menu (role_id,menu_id,tenant_id,creator,updater)
SELECT r.id,m.id,1,'foundation-v1','foundation-v1'
FROM system_role r JOIN system_menu m ON m.id IN (2000,2001,2005)
WHERE r.tenant_id=1 AND r.code='print_reader' AND r.deleted=b'0'
AND NOT EXISTS (SELECT 1 FROM system_role_menu rm WHERE rm.role_id=r.id AND rm.menu_id=m.id AND rm.tenant_id=1);

UPDATE system_tenant_package SET menu_ids=JSON_ARRAY_APPEND(menu_ids,'$',2000)
WHERE id=1 AND NOT JSON_CONTAINS(menu_ids,'2000');
UPDATE system_tenant_package SET menu_ids=JSON_ARRAY_APPEND(menu_ids,'$',2001)
WHERE id=1 AND NOT JSON_CONTAINS(menu_ids,'2001');
UPDATE system_tenant_package SET menu_ids=JSON_ARRAY_APPEND(menu_ids,'$',2002)
WHERE id=1 AND NOT JSON_CONTAINS(menu_ids,'2002');
UPDATE system_tenant_package SET menu_ids=JSON_ARRAY_APPEND(menu_ids,'$',2003)
WHERE id=1 AND NOT JSON_CONTAINS(menu_ids,'2003');
UPDATE system_tenant_package SET menu_ids=JSON_ARRAY_APPEND(menu_ids,'$',2004)
WHERE id=1 AND NOT JSON_CONTAINS(menu_ids,'2004');
UPDATE system_tenant_package SET menu_ids=JSON_ARRAY_APPEND(menu_ids,'$',2005)
WHERE id=1 AND NOT JSON_CONTAINS(menu_ids,'2005');
COMMIT;
