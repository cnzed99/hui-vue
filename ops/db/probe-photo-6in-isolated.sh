#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
cd -- "$(git rev-parse --show-toplevel)"
scratch=$(mktemp -d /tmp/print-photo-6in-db-XXXXXXXX)
[[ $(realpath -e -- "$scratch") == "$scratch" && ! -L $scratch && $scratch == /tmp/print-photo-6in-db-???????? ]]
port=${1:-45941};[[ $port =~ ^[45][0-9]{4}$ ]]
pid=''
cleanup(){ if [[ -n $pid ]] && kill -0 "$pid" 2>/dev/null;then kill "$pid";wait "$pid" || true;fi;rm -rf --one-file-system -- "$scratch"; }
trap cleanup EXIT
mkdir "$scratch/data" "$scratch/storage"
mysqld --no-defaults --initialize-insecure --datadir="$scratch/data" --log-error="$scratch/init.log"
mysqld --no-defaults --datadir="$scratch/data" --socket="$scratch/mysql.sock" --pid-file="$scratch/mysql.pid" --bind-address=127.0.0.1 --port="$port" --mysqlx=0 --innodb-buffer-pool-size=32M --performance-schema=OFF --skip-log-bin --log-error="$scratch/mysql.log" &
pid=$!
socket=(--no-defaults --protocol=socket --socket="$scratch/mysql.sock" -uroot)
for _ in $(seq 1 30);do kill -0 "$pid";if mysqladmin "${socket[@]}" ping --silent 2>/dev/null;then break;fi;sleep 1;done
[[ $(mysql "${socket[@]}" -Nse 'SELECT @@datadir') == "$scratch/data/" ]]
mysql "${socket[@]}" -e 'CREATE DATABASE photo_candidate CHARACTER SET utf8mb4 COLLATE utf8mb4_bin'
# Schema metadata only. Never copy production rows, credentials, triggers or backups into test output.
sudo -n mysqldump --no-data --skip-triggers --compact print_demo system_tenant print_store print_staff_store system_users system_menu system_role system_role_menu print_price_profile print_miniapp_demo_principal print_miniapp_demo_session print_miniapp_demo_file print_miniapp_demo_quote print_miniapp_demo_order print_miniapp_demo_order_item print_miniapp_demo_order_event print_miniapp_demo_payment_journal print_miniapp_demo_payment_receipt print_miniapp_demo_print_task print_miniapp_demo_print_task_action print_miniapp_demo_print_receipt print_miniapp_demo_cancellation print_miniapp_demo_refund_journal print_miniapp_demo_refund_receipt | mysql "${socket[@]}" --init-command="SET FOREIGN_KEY_CHECKS=0" photo_candidate
mysql "${socket[@]}" photo_candidate <<'SQL'
INSERT INTO system_tenant(id,name,contact_name,status,package_id,expire_time,account_count) VALUES(1,'fixture','fixture',0,1,CURRENT_TIMESTAMP+INTERVAL 1 DAY,100);
INSERT INTO print_store(id,tenant_id,store_code,name,status) VALUES(1,1,'fixture','fixture',0);
INSERT INTO system_users(id,username,password,nickname,status,tenant_id) VALUES(1,'fixture','fixture','fixture',0,1);
INSERT INTO print_staff_store(tenant_id,store_id,user_id) VALUES(1,1,1);
INSERT INTO system_role(id,name,code,sort,status,type,tenant_id) VALUES(1,'fixture admin','tenant_admin',1,0,2,1),(2,'fixture reader','print_reader',2,0,2,1),(3,'fixture other','other',3,0,2,1);
INSERT INTO print_price_profile VALUES('8cf69353-bc69-5039-ad47-b59e3c791c76',1,1,'MINIAPP_DEMO_A4','1','DEMO','CNY','0.10','0.20',NULL,1000);
DELIMITER //
CREATE TRIGGER trg_print_price_profile_immutable BEFORE UPDATE ON print_price_profile FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Immutable price profile'; END//
CREATE TRIGGER trg_print_demo_quote_document_only BEFORE INSERT ON print_miniapp_demo_quote FOR EACH ROW BEGIN IF (SELECT source_kind FROM print_miniapp_demo_file WHERE id=NEW.file_id)<>'DOCUMENT' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Document only';END IF;END//
DELIMITER ;
SQL
mysql "${socket[@]}" photo_candidate < ops/db/print-miniapp-demo-photo-6in.sql > target/photo-6in-checks/db-migration-readback.log
cp=$(cat target/photo-6in-checks/classpath.txt)
java -Xmx128m -cp "$cp" cn.iocoder.yudao.server.print.miniappdemo.photo.MiniappDemoPhotoMysqlChecks "$port" "$scratch/storage"
echo PHOTO_MYSQL_ISOLATED_PASS
