package dev.hotshield.coalesce;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

// Concurrent run() calls for the same key share one loader call: the first caller (the leader) runs it, the
// rest wait for its result. Once the result is delivered the key is forgotten, so a later call runs the loader
// again. This is not a cache.
public final class SingleFlight<K, V> {

    private final ConcurrentMap<K, CompletableFuture<V>> flights = new ConcurrentHashMap<>();

    public V run(K key, Supplier<V> loader) {
        var mine = new CompletableFuture<V>();
        // putIfAbsent decides the leader without running the loader inside the map. computeIfAbsent would hold
        // a bin lock for the whole database read, blocking other keys in the same bin and pinning the carrier.
        CompletableFuture<V> existing = flights.putIfAbsent(key, mine);
        if (existing != null) {
            return await(existing);
        }
        return lead(key, mine, loader);
    }

    private V lead(K key, CompletableFuture<V> flight, Supplier<V> loader) {
        try {
            V value = loader.get();
            flight.complete(value);
            return value;
        } catch (Throwable t) {
            // Throwable, not RuntimeException: a sneaky checked exception that skipped this would leave every
            // follower parked on a future nobody completes.
            flight.completeExceptionally(t);
            throw t;
        } finally {
            // Removed before run() returns, so a call that starts after this one finished runs the loader again.
            // A failure is never remembered either: the next caller retries instead of getting the old error.
            flights.remove(key, flight);
        }
    }

    // Followers get the leader's own exception, unwrapped, so coalesce mode fails with the same exception types
    // as direct mode.
    private static <V> V await(CompletableFuture<V> flight) {
        try {
            return flight.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            // Only this waiter gives up. The leader and the other waiters carry on.
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for single-flight result", e);
        }
    }
}
