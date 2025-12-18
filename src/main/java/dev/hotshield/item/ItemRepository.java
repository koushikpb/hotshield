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
        // Milestone 1 (yours): select one item by id with jdbc.sql(...).param(...).query(Item.class).optional().
        Optional<Item> item = notWritten("findById");
        findByIdQueries.increment();
        return item;
    }

    // Returns the updated row so the caller gets the new version without a second read.
    public Optional<Item> update(long id, ItemUpdate update) {
        // Milestone 1 (yours): update name, price_cents, stock; bump version; set updated_at; return the row.
        Optional<Item> item = notWritten("update");
        updateQueries.increment();
        return item;
    }

    public List<Item> findByCategory(String category, int minPriceCents, int limit) {
        // Milestone 1 (yours): this is the query for the EXPLAIN ANALYZE experiment.
        List<Item> items = notWritten("findByCategory");
        categoryQueries.increment();
        return items;
    }

    // Delete once the three queries above are written.
    private static <T> T notWritten(String query) {
        throw new UnsupportedOperationException(query + ": write this query");
    }
}
