# MonMon-Play

- A port of my personal finance GSheet using the Play Framework for Java using:
    - Java 11
    - Play 2.8.7
    - Pac4j 5.0.0
    - Keycloak 12.0.2
    - And, eventually, PostgreSQL! (for now, everything is within a h2 database)
    
- Hard dependencies:
    - Java 9
    - Docker
    - sbt
    - The will to tinker

## How to run locally

- Start up Postgres, seed postgres and start Keycloak via docker-compose

```
./run_boh.sh
```

- Provision Keycloak (requires a running keycloak web ui, this script below doesn't (currently) wait for this!)

```
./post_run_boh.sh
```

- (Missing step) Add users to the realm, it's not possible to export users using the web UI 
- one must run some sort of script in the standlone bin

- Run the Play app

```
sbt run
```

## How to deploy

- At the moment, Monmon is deployed to two tiny droplets on DigitalOcean
    - One runs postgres + the app
    - The other runs Keycloak (since Keycloak needs a minimum of 512mb RAM)

### Useful commands

- See processes by memory usage:

```
ps -o pid,user,%mem,command ax | sort -b -k3 -r
```

- Install docker / docker-compose

```
 curl -fsSL https://get.docker.com -o get-docker.sh
 sudo sh get-docker.sh
 sudo curl -L "https://github.com/docker/compose/releases/download/1.28.6/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
 sudo chmod +x /usr/local/bin/docker-compose
```

## Auth

- For my sins, auth is handled with Keycloak at present
- Start a local Keycloak through docker with:

```bash
docker run -it -p 8080:8080 -e KEYCLOAK_USER=admin -e KEYCLOAK_PASSWORD=admin -e DB_VENDOR=h2 quay.io/keycloak/keycloak:12.0.2
```

- This will run Keycloak against an in-memory h2 database
- When deployed, Keycloak runs against the same postgres db as the Play app
- Quickstarted with:
  - https://www.keycloak.org/getting-started/getting-started-docker
- To get the client secret:
  - https://stackoverflow.com/questions/44752273/do-keycloak-clients-have-a-client-secret
  - set client access type to 'confidential'
  
- For running Keycloak behind a reverse proxy:
    - https://itnext.io/nginx-as-reverse-proxy-in-front-of-keycloak-21e4b3f8ec53 

### Essentials for making OIDC work (with client/secret auth)

- Set the authentication method

```java
 oidcConfiguration.setClientAuthenticationMethod(
                ClientAuthenticationMethod.CLIENT_SECRET_BASIC
        );
```

- Set the preferred JWS algorithm:

```java
 oidcConfiguration.setPreferredJwsAlgorithm(JWSAlgorithm.RS256);
```

- Then, set the same algo in Keycloak (12):

  - realm ->
    client ->
    settings ->
    Fine Grain OpenID Connect Configuration ->
    Access Token Signature Algorithm ->
    "RS256"

- Also add this library to build.sbt. This fixes a runtime decoding problem

```
"javax.xml.bind" % "jaxb-api" % "2.3.0"
```

- https://stackoverflow.com/questions/46381242/intellij-sbt-based-scala-project-does-not-build-with-java-9

### sbt weirdness

- To clear all caches and dependencies to test what happens in the case of a 'clean install' (like on CI)

```
$ sbt clean cleanFiles
$ rm -fr ~/.ivy2/cache
```

### Useful links

- https://openid.net/connect/
- https://developer.okta.com/blog/2017/10/31/add-authentication-to-play-framework-with-oidc
- https://www.pac4j.org/docs/clients/openid-connect.html
- https://medium.com/sqooba/securing-a-single-page-app-through-openid-connect-sso-using-an-explicit-flow-with-play2-and-pac4j-7f6c4f46e31a
- https://github.com/pac4j/pac4j/issues/1189

# Extends :: play-java-jpa-example

This project demonstrates how to create a simple database application with Play, using JPA.

Please see the Play documentation for more details:

- https://www.playframework.com/documentation/latest/JavaJPA
- https://www.playframework.com/documentation/latest/ThreadPools
- https://www.playframework.com/documentation/latest/JavaAsync

# A notice about thinking that running postgres in the wild with no secops is a good idea

- I genuinely think this happened!
- https://dba.stackexchange.com/questions/207589/excessive-postgres-docker-cpu-consumption
- https://ubuntu.com/blog/postgresql-security-the-pgminer-botnet-attacks-explained

---

## Savings Plan: Priority Queue & Interlocked Sliders

### Priority Queue Mechanics (P1 .. PN)
- **Queue Rank:** Priorities are strict sequential ranks (`P1`, `P2`, `P3`, etc.). `P1` represents the highest priority debt payoff or savings target.
- **Shunting with Arrows:** Each item row includes up (`▲`) and down (`▼`) shunt buttons. Clicking an arrow swaps the item with its neighbor and automatically rebalances the entire plan to clean `1..N` integers in the database.
- **Priority Rollover:** When a debt payoff target is completed or an item is deleted (`removeItemAndRebalanceQueue`), 100% of its freed monthly budget automatically cascades into the top-priority remaining item (`P1`). This implements the classic debt avalanche/snowball principle.
- **Interlocked Sliders:**
  - The monthly savings target forms a fixed total pot (derived from income savings percentage).
  - Individual item sliders are interlocked: adjusting any unlocked item dynamically scales remaining unlocked items proportionally so that the sum of slices strictly reconciles to the target pot.
  - Locked items (`Fixed`) remain immune to automatic scaling.
  - Penny rounding errors are automatically reconciled on the final unlocked item.

---

## Outgoings: Monthly, Yearly, and N-ary Multi-Year Bills

- **Monthly Outgoings:** Recur every calendar month on their designated day of the month (1–31) with smart month-length clamping.
- **Yearly Bills (1 Year):** Run once a year. They record both a day (1–31) and a due month (1–12, Jan–Dec).
- **N-ary Multi-Year Payments (e.g. Every 2 or 3 Years):**
  - Configured with `yearInterval` ($N \ge 2$) and `startingYear`.
  - Example: A payment occurring every 2 years with `startingYear = 2025` will only roll around in 2025, 2027, 2029, etc. In 2026, it is recognized as non-due and displays `Next: 2027`.
- **Rollover to General Outgoings:**
  - When the due year and month roll around, the bill automatically appears in the active monthly outgoings list ("Monthly & Due Now") and is factored into that month's spending total and SPOG metrics.
  - In non-due months/years, it remains neatly organized under the "Yearly Bills" tab with a countdown to the next payment year, without artificially depressing current monthly disposable income.

