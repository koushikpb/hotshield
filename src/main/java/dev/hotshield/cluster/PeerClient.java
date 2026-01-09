package dev.hotshield.cluster;

import dev.hotshield.HotShieldProperties;
import dev.hotshield.item.Item;
import java.net.http.HttpClient;
import java.util.Optional;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class PeerClient {

    private final RestClient http;

    public PeerClient(RestClient.Builder builder, HotShieldProperties properties) {
        // HTTP/1.1 because the JDK client otherwise opens every connection with an h2c upgrade attempt.
        // Connect and read are bounded separately; ItemCacheConfig counts both when sizing tombstones.
        var client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.forwardTimeout())
                .build();
        var requests = new JdkClientHttpRequestFactory(client);
        // Bounds the whole wait on the owner. Past this the caller falls back to the database, so a slow or
        // dead owner costs at most this much extra latency per miss.
        requests.setReadTimeout(properties.forwardTimeout());
        this.http = builder.requestFactory(requests).build();
    }

    public void invalidate(String baseUrl, long id, long version) {
        http.post()
                .uri(baseUrl + "/internal/items/{id}/invalidate?version={version}", id, version)
                .retrieve()
                .toBodilessEntity();
    }

    // Throws RestClientException when the owner is unreachable, times out, or answers with an error.
    public Optional<Item> fetch(String baseUrl, long id) {
        return http.get()
                .uri(baseUrl + "/internal/items/{id}", id)
                .exchange((request, response) -> {
                    if (response.getStatusCode().value() == 404) {
                        return Optional.empty();
                    }
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new RestClientException("owner answered " + response.getStatusCode());
                    }
                    return Optional.ofNullable(response.bodyTo(Item.class));
                });
    }
}
