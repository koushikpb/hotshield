-- Its own migration so the EXPLAIN ANALYZE experiment can compare plans at V1 and V2.
-- Serves findByCategory: equality on category, range on price_cents, and the price_cents order comes free.
create index items_category_price_idx on items (category, price_cents);
