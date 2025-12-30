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
public class OwnerClient {

    private final RestClient http;

    public OwnerClient(RestClient.Builder builder, HotShieldProperties properties) {
        var client = HttpClient.newBuilder().connectTimeout(properties.forwardTimeout()).build();
        var requests = new JdkClientHttpRequestFactory(client);
        // Bounds the whole wait on the owner. Past this the caller falls back to the database, so a slow or
        // dead owner costs at most this much extra latency per miss.
        requests.setReadTimeout(properties.forwardTimeout());
        this.http = builder.requestFactory(requests).build();
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
