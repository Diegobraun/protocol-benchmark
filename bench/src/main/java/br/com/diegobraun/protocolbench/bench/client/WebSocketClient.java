package br.com.diegobraun.protocolbench.bench.client;

import br.com.diegobraun.protocolbench.api.Product;
import br.com.diegobraun.protocolbench.bench.Scenario;
import br.com.diegobraun.protocolbench.bench.Target;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class WebSocketClient implements ProtocolClient {

    private static final String DONE = "{\"done\":true}";
    private static final String CLOSED = "\u0000closed";
    private static final TypeReference<List<Product>> PRODUCT_LIST = new TypeReference<>() {
    };

    private final URI uri;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public WebSocketClient(Target target) {
        this.uri = URI.create(target.webSocketUrl());
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public String name() {
        return "websocket";
    }

    @Override
    public String label() {
        return "WebSocket (JSON, 1 conexão por worker)";
    }

    @Override
    public boolean supports(Scenario scenario) {
        return true;
    }

    @Override
    public Session openSession() {
        MessageListener listener = new MessageListener();
        WebSocket webSocket = http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(uri, listener)
                .join();
        return new WebSocketSession(webSocket, listener.messages);
    }

    @Override
    public void close() {
        http.close();
    }

    private final class WebSocketSession implements Session {

        private final WebSocket webSocket;
        private final BlockingQueue<String> messages;

        private WebSocketSession(WebSocket webSocket, BlockingQueue<String> messages) {
            this.webSocket = webSocket;
            this.messages = messages;
        }

        @Override
        public CallResult execute(Scenario scenario, int size, long productId) throws Exception {
            return switch (scenario) {
                case SINGLE -> {
                    String message = request("{\"op\":\"get\",\"id\":" + productId + "}");
                    Product product = mapper.readValue(message, Product.class);
                    yield new CallResult(1, message.length(), product.name());
                }
                case LIST -> {
                    String message = request("{\"op\":\"list\",\"limit\":" + size + "}");
                    List<Product> products = mapper.readValue(message, PRODUCT_LIST);
                    yield new CallResult(products.size(), message.length(), products.isEmpty() ? null : products.getFirst().name());
                }
                case STREAM -> {
                    webSocket.sendText("{\"op\":\"stream\",\"count\":" + size + "}", true).join();
                    int items = 0;
                    long bytes = 0;
                    String firstName = null;
                    String message;
                    while (!(message = next()).equals(DONE)) {
                        Product product = mapper.readValue(message, Product.class);
                        bytes += message.length();
                        if (items++ == 0) {
                            firstName = product.name();
                        }
                    }
                    yield new CallResult(items, bytes, firstName);
                }
            };
        }

        private String request(String payload) throws Exception {
            webSocket.sendText(payload, true).join();
            return next();
        }

        private String next() throws InterruptedException, IOException {
            String message = messages.poll(30, TimeUnit.SECONDS);
            if (message == null) {
                throw new IOException("Timed out waiting for WebSocket message");
            }
            if (message.startsWith(CLOSED)) {
                throw new IOException("WebSocket closed: " + message.substring(CLOSED.length()));
            }
            return message;
        }

        @Override
        public void close() {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "bye")
                    .orTimeout(5, TimeUnit.SECONDS)
                    .exceptionally(e -> null)
                    .join();
        }
    }

    private static final class MessageListener implements WebSocket.Listener {

        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                messages.add(buffer.toString());
                buffer = new StringBuilder();
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            messages.add(CLOSED + statusCode + " " + reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            messages.add(CLOSED + error);
        }
    }
}
