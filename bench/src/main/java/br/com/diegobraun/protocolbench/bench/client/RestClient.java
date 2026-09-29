package br.com.diegobraun.protocolbench.bench.client;

import br.com.diegobraun.protocolbench.api.Product;
import br.com.diegobraun.protocolbench.bench.Scenario;
import br.com.diegobraun.protocolbench.bench.Target;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

public final class RestClient implements ProtocolClient {

    private static final TypeReference<List<Product>> PRODUCT_LIST = new TypeReference<>() {
    };

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public RestClient(Target target) {
        this.baseUrl = target.httpBaseUrl();
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public String name() {
        return "rest";
    }

    @Override
    public String label() {
        return "REST (HTTP/1.1 + JSON, SSE no stream)";
    }

    @Override
    public boolean supports(Scenario scenario) {
        return true;
    }

    @Override
    public Session openSession() {
        return this::execute;
    }

    private CallResult execute(Scenario scenario, int size, long productId) throws IOException, InterruptedException {
        return switch (scenario) {
            case SINGLE -> {
                byte[] body = get("/api/products/" + productId);
                Product product = mapper.readValue(body, Product.class);
                yield new CallResult(1, body.length, product.name());
            }
            case LIST -> {
                byte[] body = get("/api/products?limit=" + size);
                List<Product> products = mapper.readValue(body, PRODUCT_LIST);
                yield new CallResult(products.size(), body.length, products.isEmpty() ? null : products.getFirst().name());
            }
            case STREAM -> stream(size);
        };
    }

    private byte[] get(String path) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = http.send(request(path), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    private CallResult stream(int size) throws IOException, InterruptedException {
        HttpResponse<Stream<String>> response = http.send(
                request("/api/products/stream?count=" + size), HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        int items = 0;
        long bytes = 0;
        String firstName = null;
        try (Stream<String> lines = response.body()) {
            Iterator<String> iterator = lines.iterator();
            while (iterator.hasNext()) {
                String line = iterator.next();
                bytes += line.length() + 1;
                if (line.startsWith("data:")) {
                    Product product = mapper.readValue(line.substring(5).trim(), Product.class);
                    if (items++ == 0) {
                        firstName = product.name();
                    }
                }
            }
        }
        return new CallResult(items, bytes, firstName);
    }

    private HttpRequest request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
    }

    @Override
    public void close() {
        http.close();
    }
}
