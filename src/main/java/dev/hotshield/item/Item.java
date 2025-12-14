package dev.hotshield.item;

import java.time.OffsetDateTime;

public record Item(
        long id,
        String sku,
        String name,
        String category,
        int priceCents,
        int stock,
        long version,
        OffsetDateTime updatedAt) {}
