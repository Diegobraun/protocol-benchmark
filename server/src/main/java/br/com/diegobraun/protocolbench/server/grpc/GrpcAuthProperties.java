package br.com.diegobraun.protocolbench.server.grpc;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties("grpc.auth")
public record GrpcAuthProperties(Map<String, String> clients) {

    public GrpcAuthProperties {
        clients = clients == null ? Map.of() : Map.copyOf(clients);
    }
}
