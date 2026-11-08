# Current Migration Progress & Next Up

## Completed:
1. **Rauthy OIDC Identity Provider**:
   - Switched from bespoke `monmon-auth` to Rauthy.
   - Configured declarative bootstrapping (`clients.json` with client `monmon-web`, RS256 token signing, `users.json` for `demo`).
   - Adapted `UserHelpers.java` and `JPAUserAccessRepository.java` to resolve `userId` dynamically against existing PostgreSQL records by username/email/sub, maintaining 100% backward compatibility with existing user data.
   - Automated test coverage: `rust-rewrite/tests-contract/rauthy.contract.test.mjs` (5/5 passing).

2. **SPOG (Single Point of Glass) Ported to monmon-rs**:
   - Migrated core domain logic: financial projections, surplus calculations, allowances, completed vs pending outgoings, debit/credit available, spending pace.
   - Fast PostgreSQL aggregations replacing heavy in-memory crunching.
   - Askama server-side HTML rendering (`rust-rewrite/monmon-rs/templates/spog.html`) preserving the 4-card hierarchy, dot indicators, and mobile swipe deck (`spog-deck.js`).
   - Behavioral test verification:
     - Contract suite: `rust-rewrite/tests-contract/spog.contract.test.mjs` (2/2 passing).
     - Playwright E2E suite: `rust-rewrite/tests-e2e/specs/spog-deck.spec.js` (4/4 passing on both Java port 9000 and Rust port 9001).
     - Full Playwright suite: 18/18 tests passing.

---

## Next Up:
1. **Savings Plan (Priority 2 Domain)**:
   - Behavioral tests first (database system test + Playwright test for priority queue operations `P1..P15`, `moveUp`, `moveDown`, `moveToTop`, `moveToBottom`, cascaded value reallocation on deletion).
   - Port Savings Plan domain engine to `monmon-rs`.
   - Askama template for `/savingsPlan`.
   - Verify parity against Java and Rust implementations.

2. **Spending Plan & NomMon Meal Plan (Priority 3 Domain)**:
   - 5-day chunk calculation and Mistral AI plan solver.

3. **Monzo OAuth & Webhooks (Priority 4 Domain)**:
   - Ingestion and token refresh.

4. **Net Worth (Priority 5 Domain)**:
   - Asset valuation timeline.
