# DB Admin-ing

## pg_dump commmands

pg_dump -p 5432 -U postgres -h monmon.urawizard.com --column-inserts -v -d monmon-play > ./backups/monmon-play-20210404.sql
pg_dump -p 5432 -U postgres -h monmon.urawizard.com --column-inserts -v -d keycloak > ./backups/keycloak-20210404.sql

## Change the password of the postgres user

