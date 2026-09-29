package br.com.diegobraun.protocolbench.server.grpc;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GrpcAuthProperties.class)
public class GrpcConfig {

    @Bean
    public GrpcAuthInterceptor grpcAuthInterceptor(GrpcAuthProperties properties) {
        return new GrpcAuthInterceptor(properties.clients());
    }

    @Bean
    public GrpcMetricsInterceptor grpcMetricsInterceptor() {
        return new GrpcMetricsInterceptor();
    }
}
