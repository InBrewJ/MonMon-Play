# MonMon-Play — Agent Guidelines

## Frontend & Scripts

- **Use Plain JavaScript (`.js`)**: If something can be implemented in a plain `.js` file (e.g. in `public/javascripts/`), do so.
- **Do Not Use CoffeeScript**: Do not lean back on CoffeeScript at all. Do not create new `.coffee` files or expand legacy CoffeeScript assets.

## Data Processing & Time Series

- **Prefer PostgreSQL (`psql`) over in-memory Java**: When performing medium to heavy data processing for time series data, favor doing aggregations, rollups, and transformations directly in PostgreSQL (e.g., repository queries, views, or migrations) rather than pulling raw records into Java and crunching them in-memory. PostgreSQL will likely be much quicker and more efficient.

## Committing and pushing

- feel free to commit and push, but your commit messages should be prefixed with \[agy\]

## Deploying

- never under and circumstances do you run deploy scripts. That is human work
  - test out deployment artifacts locally, by all means, but those artefacts are pushed
    to live by a human
