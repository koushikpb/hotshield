package dev.hotshield.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.hotshield.HotShieldProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

// A real HTTP server, so the timeout is exercised on an actual socket rather than a mock.
class PeerClientTest {

    HttpServer server;
    String baseUrl;
    PeerClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/items/1", exchange -> respond(exchange, 200, """
                {"id":1,"sku":"SKU-1","name":"kettle","category":"kitchen","priceCents":2500,"stock":10,
                 "version":3,"updatedAt":"2025-12-01T10:00:00Z"}"""));
        server.createContext("/internal/items/2", exchange -> respond(exchange, 404, ""));
        server.createContext("/internal/items/3", exchange -> respond(exchange, 503, ""));
        server.createContext("/internal/items/4", exchange -> {
            sleep(Duration.ofSeconds(2));
            respond(exchange, 200, "{}");
        });
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        var properties = new HotShieldProperties(HotShieldProperties.Mode.DISTRIBUTED, "node-a", List.of(),
                Duration.ofMillis(300), Duration.ZERO, new HotShieldProperties.Cache(10, Duration.ofSeconds(30), Duration.ofSeconds(10)));
        client = new PeerClient(RestClient.builder(), properties);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void returnsTheOwnersItem() {
        var item = client.fetch(baseUrl, 1).orElseThrow();

        assertThat(item.version()).isEqualTo(3);
        assertThat(item.priceCents()).isEqualTo(2500);
    }

    @Test
    void notFoundIsEmptyNotAnError() {
        assertThat(client.fetch(baseUrl, 2)).isEmpty();
    }

    @Test
    void serverErrorThrowsSoTheCallerFallsBack() {
        assertThatThrownBy(() -> client.fetch(baseUrl, 3)).isInstanceOf(RestClientException.class);
    }

    @Test
    void slowOwnerTimesOutWithinTheForwardTimeout() {
        long start = System.nanoTime();

        assertThatThrownBy(() -> client.fetch(baseUrl, 4)).isInstanceOf(RestClientException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
    }

    static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
