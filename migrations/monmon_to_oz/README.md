## What exists now

- monmon2.urawizard.com points to oz.urawizard.com
- the Play app is running but is still talking to PostgreSQL / Keycloak running on monmon.urawizard.com

## Plan

1. take backups of monmon / keycloak database via pg_dump
2. spin up Postgres instance for monmon / keycloak on oz.urawizard
3. restore databases from backups from step 1.
4. shut down keycloak server @ identity.urawizard.com
5. spin up Keycloak on oz.urawizard.com
6. amend identity.urawizard.com DNS records and nginx config so that identity.urawizard.com maps to oz.urawizard.com (init-letencrypt might need tweaking)
7. connect monmon2.urawizard.com to Keycloak running on oz.urawizard.com
8. check everything works, amend any fiddly nginx config / certbot stuff
9. shut down existing monmon.urawizard and identity.urawizard services and delete DO Droplets
10. Fix CircleCI deployment pipeline so that new builds are sent via circle@oz.urawizard.com
