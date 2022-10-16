# 'boh' = 'back of house'

echo Starting services in docker-compose.yaml and seeding Postgres...

docker-compose up -d --force-recreate  && \
bash -c 'while ! docker exec -ti postgres-monmon sh -c "pg_isready -U postgres"; do sleep 1; done;' && \
PGPASSFILE="./db/.pgpass" psql -U postgres -h localhost -p 5432 -d postgres -f ./db/seed.sql

