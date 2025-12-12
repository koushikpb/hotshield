# Migrations

Milestone 1: write `V1__create_items.sql` here yourself (table and the `(category, price_cents)` index).
Columns: id, sku, name, category, price_cents, stock, version, updated_at (see docs/PLAN.md).

There is no placeholder `V1` on purpose. Flyway stores a checksum for each applied migration, so an empty `V1`
applied now would make the real one fail validation later. Keep the 1M-row seed out of Flyway, because every
Testcontainers test runs all migrations.
