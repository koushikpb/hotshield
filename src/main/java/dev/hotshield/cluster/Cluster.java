package dev.hotshield.cluster;

import dev.hotshield.HotShieldProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

// Static membership from PEERS ("node-a=http://node-a:8080,node-b=..."), self included. Every node must be
// given the same list, or two nodes can disagree about who owns a key. With no PEERS this node is a cluster
// of one and owns every key; a non-empty PEERS that leaves out this node is a config error.
@Component
public class Cluster {

    private final String self;
    private final Map<String, String> baseUrls;
    private final RendezvousHash hash;

    public Cluster(HotShieldProperties properties) {
        this.self = properties.nodeId();
        var urls = parse(properties.peers());
        if (urls.isEmpty()) {
            urls.put(self, "");
        } else if (!urls.containsKey(self)) {
            throw new IllegalArgumentException("PEERS must include this node: " + self);
        }
        this.baseUrls = urls;
        this.hash = new RendezvousHash(List.copyOf(baseUrls.keySet()));
    }

    static LinkedHashMap<String, String> parse(List<String> peers) {
        var urls = new LinkedHashMap<String, String>();
        for (String peer : peers) {
            int eq = peer.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("peer must be id=url: " + peer);
            }
            urls.put(peer.substring(0, eq).trim(), peer.substring(eq + 1).trim());
        }
        return urls;
    }

    public String ownerOf(long key) {
        return hash.ownerOf(key);
    }

    public boolean isSelf(String node) {
        return self.equals(node);
    }

    public List<String> peerUrls() {
        return baseUrls.entrySet().stream()
                .filter(entry -> !isSelf(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();
    }

    public String baseUrl(String node) {
        return baseUrls.get(node);
    }
}
