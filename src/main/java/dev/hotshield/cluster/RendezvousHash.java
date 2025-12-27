package dev.hotshield.cluster;

import java.util.List;

// Milestone 5 (yours). Picks the owner of a key: score every node against the key and take the highest.
// Every node computes the same answer from the same node list, with no coordination.
// The signature is a suggestion; change it if you change RendezvousHashTest with it.
public final class RendezvousHash {

    public RendezvousHash(List<String> nodes) {
    }

    public String ownerOf(long key) {
        throw new UnsupportedOperationException("RendezvousHash.ownerOf: write this");
    }
}
