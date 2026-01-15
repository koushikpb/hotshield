package dev.hotshield.item;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ItemRepository {

    private final JdbcClient jdbc;
    // The primary "DB queries/s" number in every experiment. Counted here, not in Postgres, so it can't miss
    // queries the way an uncreated or unreset pg_stat_statements can. Counted after the query returns, so a
    // pool timeout under overload doesn't inflate the number.
    private final Counter findByIdQueries;
    private final Counter updateQueries;
    private final Counter categoryQueries;

    public ItemRepository(JdbcClient jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.findByIdQueries = Counter.builder("db.queries").tag("op", "findById").register(registry);
        this.updateQueries = Counter.builder("db.queries").tag("op", "update").register(registry);
        this.categoryQueries = Counter.builder("db.queries").tag("op", "category").register(registry);
    }

    public Optional<Item> findById(long id) {
        Optional<Item> item = jdbc.sql("""
                        select id, sku, name, category, price_cents, stock, version, updated_at
                        from items
                        where id = :id
                        """)
                .param("id", id)
                .query(Item.class)
                .optional();
        findByIdQueries.increment();
        return item;
    }

    // Returns the updated row so the caller gets the new version without a second read. The version bump is
    // part of the same statement, so two concurrent PUTs can't both end up with the same version.
    public Optional<Item> update(long id, ItemUpdate update) {
        Optional<Item> item = jdbc.sql("""
                        update items
                        set name = :name, price_cents = :priceCents, stock = :stock,
                            version = version + 1, updated_at = now()
                        where id = :id
                        returning id, sku, name, category, price_cents, stock, version, updated_at
                        """)
                .param("id", id)
                .param("name", update.name())
                .param("priceCents", update.priceCents())
                .param("stock", update.stock())
                .query(Item.class)
                .optional();
        updateQueries.increment();
        return item;
    }

    // The query for the EXPLAIN ANALYZE experiment. The (category, price_cents) index in V2 matches both the
    // filter and the order by, so Postgres can stop after `limit` rows instead of sorting the whole category.
    public List<Item> findByCategory(String category, int minPriceCents, int limit) {
        List<Item> items = jdbc.sql("""
                        select id, sku, name, category, price_cents, stock, version, updated_at
                        from items
                        where category = :category and price_cents >= :minPriceCents
                        order by price_cents
                        limit :limit
                        """)
                .param("category", category)
                .param("minPriceCents", minPriceCents)
                .param("limit", limit)
                .query(Item.class)
                .list();
        categoryQueries.increment();
        return items;
    }
}
