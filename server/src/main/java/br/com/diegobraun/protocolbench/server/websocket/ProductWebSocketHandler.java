package br.com.diegobraun.protocolbench.server.websocket;

import br.com.diegobraun.protocolbench.api.Product;
import br.com.diegobraun.protocolbench.api.ProductCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;

@Component
public class ProductWebSocketHandler extends TextWebSocketHandler {

    public static final String DONE = "{\"done\":true}";

    private final ObjectMapper objectMapper;

    public ProductWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        JsonNode request = objectMapper.readTree(message.getPayload());
        switch (request.path("op").asText()) {
            case "get" -> send(session, ProductCatalog.get(request.path("id").asLong()));
            case "list" -> send(session, ProductCatalog.list(request.path("limit").asInt()));
            case "stream" -> {
                for (Product product : ProductCatalog.list(request.path("count").asInt())) {
                    send(session, product);
                }
                session.sendMessage(new TextMessage(DONE));
            }
            default -> session.sendMessage(new TextMessage("{\"error\":\"unknown op\"}"));
        }
    }

    private void send(WebSocketSession session, Object payload) throws IOException {
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
    }
}
