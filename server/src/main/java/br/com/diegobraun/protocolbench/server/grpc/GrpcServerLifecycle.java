package br.com.diegobraun.protocolbench.server.grpc;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.TimeUnit;

@Component
public class GrpcServerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);

    private final int configuredPort;
    private Server server;

    public GrpcServerLifecycle(@Value("${grpc.port:9090}") int configuredPort) {
        this.configuredPort = configuredPort;
    }

    @Override
    public void start() {
        try {
            server = NettyServerBuilder.forPort(configuredPort)
                    .addService(new ProductGrpcService())
                    .maxInboundMessageSize(64 * 1024 * 1024)
                    .build()
                    .start();
            log.info("gRPC server started on port {}", server.getPort());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void stop() {
        if (server == null) {
            return;
        }
        server.shutdown();
        try {
            server.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        server = null;
    }

    @Override
    public boolean isRunning() {
        return server != null;
    }

    public int port() {
        return server.getPort();
    }
}
