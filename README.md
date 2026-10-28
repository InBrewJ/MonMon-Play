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

### Priority Queue Algorithm & Mechanics (P1 .. PN)

#### How Priority Values are Calculated & Why the Bug Happened
1. **The Intended Model**:
   - Each active item in a user's savings plan represents a debt payoff or savings target with a discrete queue rank: `P1, P2, P3, ... PN`.
   - `P1` is the highest-priority target in the queue.
   - When items are moved up or down using the shunt arrows (`▲` and `▼`), the item swaps places with its neighbor and the entire active queue is re-indexed from `1` to `N` contiguously (`1, 2, 3, ...`).
   - When an item is deleted or paid off, its monthly budget allocation rolls over into `P1` (debt avalanche/snowball), and the remaining items shift up one rank (`P(k) -> P(k-1)`).

2. **Root Cause Analysis: Why did items jump to P1, P23, P24 and why were down arrows disabled?**:
   - **Non-contiguous initial priorities**: In `SavingsPlanController.addAllCreditCards` and `addAllOverdrafts`, items were appended in a loop using `int priority = activePlan.getItems().size() + 1; priority++`. In an account with 32 accounts, if accounts 22, 23, 24 were credit accounts with balances, their raw priorities were set to 23, 24, etc.
   - **Controller rendering bypassed self-healing**: `JPASavingsPlanRepository.getActive(userId)` contained a self-healing check to rebalance priorities to contiguous `1..N`. However, `SavingsPlanController.renderSavingsPlanView` did NOT call `getActive(userId)`—it called `savingsPlanRepository.listComplete(userId)`, which performed a raw JPA query without running the self-healing routine! As soon as any edit occurred, raw gaps from previous operations or deleted items were exposed.
   - **Broken reference equality on archive**: `SavingsPlanItem` did not implement `equals()` and `hashCode()`. When `removeItemAndRebalanceQueue(item)` checked `if (!active.contains(itemToRemove)) return;`, JPA object reference mismatch caused the method to abort silently without archiving or rebalancing remaining items, leaving disconnected priority gaps in the database.
   - **Broken Down Arrow condition in UI**: In `savingsPlan.scala.html`, the down arrow disable condition was written as:
     `@if(item.getPriority() >= activePlan.getItems().size()){disabled}`
     If a plan had 3 active items but their stored priorities were `P1`, `P23`, and `P24`, the second item (`P23`) evaluated `23 >= 3 -> true`. Thus, the down arrow was permanently disabled even though it was only the 2nd item in a 3-item list!
   - **Strict position-based indexing**: The down arrow and up arrow must be disabled strictly based on the item's 0-based index in the active list (`index == 0` for up, `index == items.size() - 1` for down), never by checking the priority integer against total item count.

3. **The Fix Plan**:
   - Make all priority assignment, shunting, and archiving strictly deterministic and self-healing:
     - On any fetch (both `getActive` and `listComplete`/`renderSavingsPlanView`), self-heal non-contiguous priorities to clean `1..N` integers in the database.
     - Implement proper `equals()` and `hashCode()` on `SavingsPlanItem` based on entity `id`.
     - In `moveItemUp` and `moveItemDown`, locate the item by `id`, swap with its predecessor/successor in the active list, and rewrite priorities `1..N`.
     - In `savingsPlan.scala.html`, use `items.zipWithIndex` to disable the up arrow on index 0 and down arrow on index `size - 1`.

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

---

## Custom Spending Plans: N Arbitrary Headings & User Templates

1. **Arbitrary Headings ($N$ Sections)**:
   - Monthly pots are no longer restricted to the legacy fixed 4 categories (Food, Fuel, Transport, Fun).
   - Users can create any number of custom sections (e.g. Groceries, Commute, Coffee & Treats, Hobbies, Gym, Holiday Fund).
   - Each section has a heading, planned amount, and optional linked account.
   - Any money left over in the pot after planned essential sections is automatically allocated to disposable Fun.
   - Pro-rated daily targets adapt dynamically as days elapse in the cycle.

2. **Saved User Templates (`CustomSpendingPlan`)**:
   - Custom configurations can be saved directly to the user's account with a template name.
   - When creating future monthly pots, users can select any of their saved templates from a dropdown, automatically prefilling the section builder.
   - Full backward compatibility: legacy pots without sections fall back cleanly to the legacy Food, Fuel, Transport, Fun columns.

---

## Accurate Mode: Available-Based Ledger (ABL) vs Transaction-Based Ledger (TBL)

### 1. The Dual-Ledger Concept
- **Available-Based Ledger (ABL)**:
  - Spend is derived from the net delta between start and end balance snapshots across cycle boundaries.
  - Indispensable for manual savings accounts, pensions, mortgages, and loans where direct Open Banking feeds are either non-existent or irrelevant to monthly disposable tracking.
- **Transaction-Based Ledger (TBL / Accurate Mode)**:
  - Directly sums individual line-item debits and credits from Open Banking feeds or uploaded bank statements.
  - Eliminates discrepancies caused by pending holds, delayed postings, or intra-month transfers.
  - Hybrid unification: Accounts with transaction feeds use TBL, while pensions/savings/unlinked accounts continue using ABL.

### 2. Live Bank Feeds: Monzo Developer API (Debit Current Accounts & Monzo Flex Credit Cards)
- **Zero Cost & Free Developer Platform**:
  - Unlike aggregators (TrueLayer, Tink, Yapily) that charge heavy monthly minimums or require enterprise sales calls, and GoCardless which closed public signups, **Monzo provides an official, 100% free developer API** ([developers.monzo.com](https://developers.monzo.com)).
  - Supports both instant **Personal Access Tokens (PAT)** (copy-pasted from the developer portal) and **OAuth 2.0 authorization code flow**.
- **Supported Account Types**:
  - **Debit Accounts (`uk_retail` & `uk_retail_joint`)**: Personal current accounts and joint accounts.
  - **Monzo Flex (`uk_monzo_flex`)**: Monzo's credit card / installment credit line is directly supported. Querying `GET /transactions?account_id={flex_id}` pulls all Flex transactions.
- **Amounts & Currency**:
  - Monzo stores amounts in integer pence (e.g. `-2450` for £24.50 spend). MonMon automatically normalizes pence into pounds and extracts rich merchant metadata.
- **Incompatible Accounts (Pensions, Mortgages & Loans)**:
  - Open Banking standards do not provide consumer transaction APIs for pensions (SIPP, Nest) or mortgages. MonMon automatically keeps these on ABL (Available-Based Ledger) balance delta tracking.

### 3. Non-Destructive Ledger Mode Switch (TBL vs ABL)
- **100% Data Preservation**:
  - `balance` records and `bank_transaction` records live in completely separate tables.
  - Toggling an account between **TBL** (Transaction-Based) and **ABL** (Available-Based) on `/bank` NEVER deletes any historical balances or transactions.
  - When switched to ABL, MonMon calculates spend using start and end balance deltas (`startBal - endBal + inflow`).
  - When switched to TBL, MonMon calculates spend from exact line-item transaction debits.
  - Older historical cycles prior to connecting Monzo automatically render using historical balance snapshots, ensuring uninterrupted continuity.

### 4. Handling 32 Accounts at Scale & High-Performance Rollups
- **MockBankSyncProvider**:
  - Simulates high-volume transaction feeds across all 32 accounts in under 150ms.
- **PostgreSQL Group-By Rollup**:
  - Aggregates monthly spend per account directly inside PostgreSQL (`GROUP BY t.accountId`) rather than pulling raw records into Java memory:
    ```sql
    SELECT t.accountId, COALESCE(SUM(t.amount), 0.0)
    FROM bank_transaction t
    WHERE t.userId = :userId AND t.timestamp >= :startSec AND t.timestamp <= :endSec AND t.amount > 0
    GROUP BY t.accountId
    ```

### 5. Statement Upload & "Dangerously Rewrite History"
- Users can upload or paste CSV bank statements for non-Monzo accounts (Amex, Barclaycard, HSBC, Lloyds).
- **Dangerously Rewrite History Option**:
  - Purges existing transactions within the statement's date range for that specific account and re-inserts the clean statement transactions.
  - Enables repairing corrupted transaction feeds with reconciled official statements.

