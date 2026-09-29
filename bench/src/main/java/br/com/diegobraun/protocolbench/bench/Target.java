package br.com.diegobraun.protocolbench.bench;

public record Target(String host, int httpPort, int grpcPort) {

    public String httpBaseUrl() {
        return "http://" + host + ":" + httpPort;
    }

    public String webSocketUrl() {
        return "ws://" + host + ":" + httpPort + "/ws";
    }
}
