package dev.hotshield.item;

import dev.hotshield.HotShieldProperties;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class ItemService {

    private final ItemRepository repository;
    private final HotShieldProperties.Mode mode;

    public ItemService(ItemRepository repository, HotShieldProperties properties) {
        this.repository = repository;
        this.mode = properties.mode();
    }

    public Optional<Item> get(long id) {
        return switch (mode) {
            case DIRECT -> repository.findById(id);
            case CACHE, COALESCE, DISTRIBUTED -> throw new UnsupportedOperationException("mode not built yet: " + mode);
        };
    }

    public Optional<Item> update(long id, ItemUpdate update) {
        return repository.update(id, update);
    }

    public List<Item> findByCategory(String category, int minPriceCents, int limit) {
        return repository.findByCategory(category, minPriceCents, limit);
    }
}
