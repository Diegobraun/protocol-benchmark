package br.com.diegobraun.protocolbench.bench.client;

import br.com.diegobraun.protocolbench.api.Product;
import br.com.diegobraun.protocolbench.bench.Scenario;
import br.com.diegobraun.protocolbench.bench.Target;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class GraphqlClient implements ProtocolClient {

    private static final String FIELDS = "id name description price category tags stock createdAt";
    private static final String PRODUCT_QUERY = "query($id: Int!) { product(id: $id) { " + FIELDS + " } }";
    private static final String PRODUCTS_QUERY = "query($limit: Int!) { products(limit: $limit) { " + FIELDS + " } }";
    private static final TypeReference<List<Product>> PRODUCT_LIST = new TypeReference<>() {
    };

    private final URI endpoint;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public GraphqlClient(Target target) {
        this.endpoint = URI.create(target.httpBaseUrl() + "/graphql");
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public String name() {
        return "graphql";
    }

    @Override
    public String label() {
        return "GraphQL (HTTP/1.1 + JSON)";
    }

    @Override
    public boolean supports(Scenario scenario) {
        return scenario != Scenario.STREAM;
    }

    @Override
    public Session openSession() {
        return this::execute;
    }

    private CallResult execute(Scenario scenario, int size, long productId) throws IOException, InterruptedException {
        return switch (scenario) {
            case SINGLE -> {
                byte[] body = post(PRODUCT_QUERY, Map.of("id", productId));
                Product product = mapper.treeToValue(data(body).path("product"), Product.class);
                yield new CallResult(1, body.length, product.name());
            }
            case LIST -> {
                byte[] body = post(PRODUCTS_QUERY, Map.of("limit", size));
                List<Product> products = mapper.treeToValue(data(body).path("products"), PRODUCT_LIST);
                yield new CallResult(products.size(), body.length, products.isEmpty() ? null : products.getFirst().name());
            }
            case STREAM -> throw new UnsupportedOperationException("GraphQL streaming requires subscriptions");
        };
    }

    private byte[] post(String query, Map<String, Object> variables) throws IOException, InterruptedException {
        byte[] payload = mapper.writeValueAsBytes(Map.of("query", query, "variables", variables));
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    private JsonNode data(byte[] body) throws IOException {
        JsonNode root = mapper.readTree(body);
        if (root.hasNonNull("errors")) {
            throw new IOException("GraphQL error: " + root.get("errors"));
        }
        return root.path("data");
    }

    @Override
    public void close() {
        http.close();
    }
}
