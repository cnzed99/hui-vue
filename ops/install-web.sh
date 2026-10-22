#!/usr/bin/env bash
set -euo pipefail
cd /home/ubuntu/print-system
sudo -n systemctl mask nginx.service
sudo -n env DEBIAN_FRONTEND=noninteractive apt-get install -y nginx
sudo -n install -d -o ubuntu -g www-data -m 755 /var/www/print-system
cp -a src/frontend/dist/. /var/www/print-system/
sudo -n find /var/www/print-system -type d -exec chmod 755 {} +
sudo -n find /var/www/print-system -type f -exec chmod 644 {} +
sudo -n tee /etc/nginx/sites-available/print-system >/dev/null <<'CONF'
server {
    listen 127.0.0.1:18080;
    server_name localhost;
    root /var/www/print-system;
    index index.html;
    server_tokens off;
    add_header X-Content-Type-Options nosniff always;
    add_header Referrer-Policy same-origin always;
    location /admin-api/ {
        proxy_pass http://127.0.0.1:48080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
    location ~ /\. { deny all; }
    location / { try_files $uri $uri/ /index.html; }
}
CONF
if [ -L /etc/nginx/sites-enabled/default ] && [ "$(readlink -f /etc/nginx/sites-enabled/default)" = /etc/nginx/sites-available/default ]; then sudo -n unlink /etc/nginx/sites-enabled/default; fi
sudo -n ln -sfn /etc/nginx/sites-available/print-system /etc/nginx/sites-enabled/print-system
sudo -n nginx -t
sudo -n systemctl unmask nginx.service
sudo -n systemctl enable --now nginx
sudo -n apt-get clean
curl -fSs -o /dev/null http://127.0.0.1:18080/
printf 'WEB_INSTALL_OK\n'
df -h /
