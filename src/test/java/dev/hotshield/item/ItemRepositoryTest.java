package dev.hotshield.item;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ItemRepository.class, SimpleMeterRegistry.class})
@Testcontainers
class ItemRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    ItemRepository repository;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MeterRegistry registry;

    @BeforeEach
    void insertFixtures() {
        jdbc.sql("""
                insert into items (id, sku, name, category, price_cents, stock, version, updated_at) values
                  (1, 'SKU-1', 'kettle', 'kitchen', 2500, 10, 1, now() - interval '1 hour'),
                  (2, 'SKU-2', 'pan',    'kitchen', 4000,  5, 1, now() - interval '1 hour'),
                  (3, 'SKU-3', 'mug',    'kitchen',  800, 50, 1, now() - interval '1 hour'),
                  (4, 'SKU-4', 'lamp',   'home',    3000,  2, 1, now() - interval '1 hour')
                """).update();
    }

    @Test
    void findsItemById() {
        var item = repository.findById(1).orElseThrow();

        assertThat(item.sku()).isEqualTo("SKU-1");
        assertThat(item.priceCents()).isEqualTo(2500);
        assertThat(item.version()).isEqualTo(1);
    }

    @Test
    void missingIdIsEmpty() {
        assertThat(repository.findById(999)).isEmpty();
    }

    @Test
    void updateBumpsVersionAndTimestamp() {
        var before = repository.findById(1).orElseThrow();

        var after = repository.update(1, new ItemUpdate("steel kettle", 2700, 9)).orElseThrow();

        assertThat(after.version()).isEqualTo(before.version() + 1);
        assertThat(after.updatedAt()).isAfter(before.updatedAt());
        assertThat(after.name()).isEqualTo("steel kettle");
        assertThat(repository.findById(1).orElseThrow()).isEqualTo(after);
    }

    @Test
    void updateOfMissingIdIsEmpty() {
        assertThat(repository.update(999, new ItemUpdate("x", 1, 1))).isEmpty();
    }

    @Test
    void findsCategoryAtOrAboveMinPriceCheapestFirst() {
        var items = repository.findByCategory("kitchen", 2500, 10);

        assertThat(items).extracting(Item::id).containsExactly(1L, 2L);
    }

    @Test
    void categoryResultsRespectLimit() {
        assertThat(repository.findByCategory("kitchen", 0, 2)).hasSize(2);
    }

    // The registry lives in the cached test context, so other tests' queries are already counted. Compare deltas.
    @Test
    void everyCallCountsOneQuery() {
        double totalBefore = totalQueries();
        double findByIdBefore = findByIdQueries();

        repository.findById(1);
        repository.findById(999);
        repository.findByCategory("home", 0, 10);

        assertThat(totalQueries() - totalBefore).isEqualTo(3);
        assertThat(findByIdQueries() - findByIdBefore).isEqualTo(2);
    }

    private double totalQueries() {
        return registry.get("db.queries").counters().stream().mapToDouble(Counter::count).sum();
    }

    private double findByIdQueries() {
        return registry.get("db.queries").tag("op", "findById").counter().count();
    }
}
