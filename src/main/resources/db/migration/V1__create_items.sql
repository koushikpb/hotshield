-- Plain bigint, not identity: the seed script and the repository test fixtures both choose their own ids.
create table items (
    id          bigint primary key,
    sku         text        not null unique,
    name        text        not null,
    category    text        not null,
    price_cents int         not null,
    stock       int         not null,
    version     bigint      not null default 1,
    updated_at  timestamptz not null default now()
);

-- Cross-check for the db.queries counter. Creating it works without shared_preload_libraries (as in
-- Testcontainers); only reading the view needs the library loaded.
create extension if not exists pg_stat_statements;
