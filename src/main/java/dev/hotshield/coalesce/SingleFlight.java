package dev.hotshield.coalesce;

import java.util.function.Supplier;

// Milestone 3 (yours). Concurrent run() calls for the same key share one loader call: the first caller
// (the leader) runs it, the rest wait for its result. Once the result is delivered the key is forgotten,
// so a later call runs the loader again. This is not a cache.
// The signature is a suggestion; change it if you change SingleFlightTest with it.
public final class SingleFlight<K, V> {

    public V run(K key, Supplier<V> loader) {
        throw new UnsupportedOperationException("SingleFlight.run: write this");
    }
}
