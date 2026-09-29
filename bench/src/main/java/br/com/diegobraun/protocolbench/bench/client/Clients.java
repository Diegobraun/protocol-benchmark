package br.com.diegobraun.protocolbench.bench.client;

import br.com.diegobraun.protocolbench.bench.Target;

import java.util.List;
import java.util.function.Function;

public final class Clients {

    public static final List<String> ALL = List.of("rest", "graphql", "grpc", "websocket");

    private Clients() {
    }

    public static ProtocolClient create(String name, Target target) {
        Function<Target, ProtocolClient> factory = switch (name.toLowerCase()) {
            case "rest" -> RestClient::new;
            case "graphql" -> GraphqlClient::new;
            case "grpc" -> GrpcClient::new;
            case "websocket" -> WebSocketClient::new;
            default -> throw new IllegalArgumentException("Unknown protocol: " + name);
        };
        return factory.apply(target);
    }
}
