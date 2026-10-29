# MonMon-Play — Agent Guidelines

## Frontend & Scripts

- **Use Plain JavaScript (`.js`)**: If something can be implemented in a plain `.js` file (e.g. in `public/javascripts/`), do so.
- **Do Not Use CoffeeScript**: Do not lean back on CoffeeScript at all. Do not create new `.coffee` files or expand legacy CoffeeScript assets.

## Mobile & Cross-Platform UX Primitives

The following UX patterns have been established as core primitives and should be applied consistently across all views:

- **Swipe-able Cards with Dot Headings (`spog-deck` / Swipe Deck Primitive)**:
  - On mobile, dense data causes fatigue and visual clutter. Use horizontal swipe-able cards paired with card headings featuring an indicator dot (see `spog-deck.js` and `.spog-card`) for all data-heavy pages (e.g., Bank Sync, Savings Plan, Net Worth, Outgoings).
- **Stacked Action Card Pattern for Mobile Tables (`/myAccounts` Primitive)**:
  - On mobile viewports, the responsive table card implementation from `/myAccounts` is the cleanest and is our designated standard primitive:
    1. Long text / account title on a single line on the left, status badge on the right.
    2. Concise stat grid cells below the header line.
    3. Action buttons (`btn-action`, e.g. Statement, Edit, Delete) placed on their own line underneath the content rather than crammed inline.
  - Standard name: **"Stacked Action Card"**. Use this pattern for all responsive mobile tables.
- **"At a Glance" Upfront Information Hierarchy (Mobile & Desktop)**:
  - For both swipe-able mobile card decks and desktop card layouts, decide up front what the user needs to see **"at a glance"** (primary headline figure, urgent due item, or key balance).
  - This decision directly informs the ordering of the page: critical overview information comes first, followed by granular breakdowns and secondary management tables.

## Data Processing & Time Series

- **Prefer PostgreSQL (`psql`) over in-memory Java**: When performing medium to heavy data processing for time series data, favor doing aggregations, rollups, and transformations directly in PostgreSQL (e.g., repository queries, views, or migrations) rather than pulling raw records into Java and crunching them in-memory. PostgreSQL will likely be much quicker and more efficient.

## System Tests (End-to-End & Realistic Environment)

- **Must run against real infrastructure**: Real PostgreSQL database (not H2 in-memory), real MonMon backend container, and real LegMon container.
- **Why system tests matter**: In-memory test databases (like H2) have loose type casting and cannot catch dialect- or engine-specific bugs (such as PostgreSQL's strict binary `jsonb` column vs. JDBC `character varying` mismatch `PSQLException 42804`, or real HTTP/Docker network interactions between LegMon and MonMon).
- **Execution & Tolerance**: System tests take longer to run due to container initialization, database migrations, and real HTTP lifecycle, but they mirror realistic production runtime and are essential for catching production regressions before shipping.
- **Automated Runner**: Run `./run_system_tests.sh` to execute the full end-to-end system test suite against real Dockerized PostgreSQL, MonMon, and LegMon.

## Committing and pushing

- feel free to commit and push, but your commit messages should be prefixed with \[agy\]

## Deploying

- never under and circumstances do you run deploy scripts. That is human work
  - test out deployment artifacts locally, by all means, but those artefacts are pushed
    to live by a human
