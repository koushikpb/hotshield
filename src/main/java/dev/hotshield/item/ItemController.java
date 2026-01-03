package dev.hotshield.item;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ItemController {

    private final ItemService items;

    public ItemController(ItemService items) {
        this.items = items;
    }

    @GetMapping("/items/{id}")
    public ResponseEntity<Item> get(@PathVariable long id) {
        return ResponseEntity.of(items.get(id));
    }

    @GetMapping("/internal/items/{id}")
    public ResponseEntity<Item> getAsOwner(@PathVariable long id) {
        return ResponseEntity.of(items.getAsOwner(id));
    }

    @PostMapping("/internal/items/{id}/invalidate")
    public ResponseEntity<Void> invalidate(@PathVariable long id, @RequestParam long version) {
        items.invalidateLocal(id, version);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/items/{id}")
    public ResponseEntity<Item> update(@PathVariable long id, @RequestBody ItemUpdate update) {
        if (update.name() == null) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.of(items.update(id, update));
    }

    // Capped so one request can't pull a whole category into memory.
    @GetMapping("/items")
    public List<Item> findByCategory(
            @RequestParam String category,
            @RequestParam(defaultValue = "0") int minPrice,
            @RequestParam(defaultValue = "50") int limit) {
        return items.findByCategory(category, minPrice, Math.clamp(limit, 1, 200));
    }
}
