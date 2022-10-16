## What exists now

- monmon2.urawizard.com points to oz.urawizard.com
- the Play app is running but is still talking to PostgreSQL / Keycloak running on monmon.urawizard.com

## Plan

1. take backups of monmon / keycloak database via pg_dump
2. spin up Postgres instance for monmon / keycloak on oz.urawizard
3. restore databases from backups from step 1.

```sh

psql -U postgres -h localhost -p 5433 -d keycloak -f keycloak-20221016.sql

psql -U postgres -h localhost -p 5433 -d monmon-play -f monmon-play-20221016.sql

```

4. shut down keycloak server @ identity.urawizard.com
5. spin up Keycloak on oz.urawizard.com @ identity2.urawizard.com
6. amend identity.urawizard.com DNS records and nginx config so that identity.urawizard.com maps to oz.urawizard.com (init-letencrypt might need tweaking)
7. connect monmon2.urawizard.com to Keycloak running on oz.urawizard.com

Note: had to regenerate an app key to allow successful signins from monmon2.urawizard.com

The consistent error in the play logs:

org.pac4j.core.exception.technicalexception: Bad token response, error=null]

regenerating the client secret in the Keycloak admin console and then updating the Play prod.conf
seemed to do the trick!

8. check everything works, amend any fiddly nginx config / certbot stuff
9. shut down existing monmon.urawizard and identity.urawizard services and delete DO Droplets
10. Fix CircleCI deployment pipeline so that new builds are sent via circle@oz.urawizard.com
11. If the mood calls for it, change DNS config so that monmon.urawizard.com points to oz.urawizard
12. Do the fiddly bits with certbot to generate another certificate for monmon.urawizard
13. If the mood calls for it, change DNS config so that identity.urawizard.com points to oz.urawizard
14. Do the fiddly bits with certbot to generate another certificate for identity.urawizard
