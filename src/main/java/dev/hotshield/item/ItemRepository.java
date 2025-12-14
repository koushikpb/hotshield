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
    // queries the way an uncreated or unreset pg_stat_statements can.
    private final Counter queries;

    public ItemRepository(JdbcClient jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.queries = Counter.builder("db.queries").register(registry);
    }

    public Optional<Item> findById(long id) {
        queries.increment();
        // Milestone 1 (yours): select one item by id with jdbc.sql(...).param(...).query(Item.class).optional().
        throw new UnsupportedOperationException("findById: write this query");
    }

    // Returns the updated row so the caller gets the new version without a second read.
    public Optional<Item> update(long id, ItemUpdate update) {
        queries.increment();
        // Milestone 1 (yours): update name, price_cents, stock; bump version; set updated_at; return the row.
        throw new UnsupportedOperationException("update: write this query");
    }

    public List<Item> findByCategory(String category, int minPriceCents, int limit) {
        queries.increment();
        // Milestone 1 (yours): this is the query for the EXPLAIN ANALYZE experiment.
        throw new UnsupportedOperationException("findByCategory: write this query");
    }
}
