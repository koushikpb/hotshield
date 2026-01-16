-- Fills items with ids 1..rows (default 1,000,000, the k6 MAX_ID default). Not a Flyway migration, because every
-- Testcontainers test runs all migrations. Rerun after mixed.js or herd.js, which write rows.
--   psql -h localhost -p 5434 -U hotshield hotshield -f loadtest/seed.sql
--   psql ... -v rows=100000 -f loadtest/seed.sql
-- Without this, psql prints an error, keeps going and exits 0, which looks like a successful seed.
\set ON_ERROR_STOP on
\if :{?rows}
\else
\set rows 1000000
\endif

-- Every value comes from the id, not random(), so a reseed gives the same rows and the same plans.
begin;
truncate items;
insert into items (id, sku, name, category, price_cents, stock, version, updated_at)
select id,
       'SKU-' || id,
       'item ' || id,
       'cat-' || lpad((id % 50)::text, 2, '0'),
       100 + (id * 7919) % 100000,
       (id * 31) % 500,
       1,
       now()
-- bigint, or id * 7919 overflows int above id 271,196 and the whole insert rolls back.
from generate_series(1::bigint, :rows) as id;
commit;

-- Fresh statistics, so the first EXPLAIN ANALYZE after a seed doesn't plan from an empty table.
analyze items;
