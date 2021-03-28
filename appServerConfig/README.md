# Dockerised nginx config for app server (currently at monmon.urawizard.com)

## SSL bootstrap

- See https://github.com/InBrewJ/auto_ssl_nginx
- Change the values in the `init-letsencrypt.sh` script

## Nginx config

- `/data/nginx/app.conf` assumes that MonMon will be running on localhost:9000 on the remote server