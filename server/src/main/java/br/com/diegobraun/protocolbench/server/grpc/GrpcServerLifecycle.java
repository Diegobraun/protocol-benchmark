package br.com.diegobraun.protocolbench.server.grpc;

import br.com.diegobraun.protocolbench.api.grpc.ProductServiceGrpc;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
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
    private final GrpcAuthInterceptor authInterceptor;
    private final GrpcMetricsInterceptor metricsInterceptor;
    private final HealthStatusManager health = new HealthStatusManager();
    private Server server;

    public GrpcServerLifecycle(@Value("${grpc.port:9090}") int configuredPort,
                               GrpcAuthInterceptor authInterceptor,
                               GrpcMetricsInterceptor metricsInterceptor) {
        this.configuredPort = configuredPort;
        this.authInterceptor = authInterceptor;
        this.metricsInterceptor = metricsInterceptor;
    }

    @Override
    public void start() {
        try {
            server = NettyServerBuilder.forPort(configuredPort)
                    .addService(ServerInterceptors.intercept(new ProductGrpcService(), authInterceptor, metricsInterceptor))
                    .addService(health.getHealthService())
                    .addService(ProtoReflectionServiceV1.newInstance())
                    .maxInboundMessageSize(64 * 1024 * 1024)
                    .build()
                    .start();
            health.setStatus(ProductServiceGrpc.SERVICE_NAME, HealthCheckResponse.ServingStatus.SERVING);
            log.info("gRPC server started on port {} (auth {})", server.getPort(),
                    authInterceptor.enabled() ? "enabled" : "disabled");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void stop() {
        if (server == null) {
            return;
        }
        health.enterTerminalState();
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
