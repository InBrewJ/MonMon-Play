SELECT 'CREATE DATABASE "monmon-play"'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'monmon-play')\gexec

SELECT 'CREATE DATABASE keycloak'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'keycloak')\gexec