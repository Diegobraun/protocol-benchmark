package br.com.diegobraun.protocolbench.server.grpc;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class GrpcMetricsController {

    private final GrpcMetricsInterceptor metrics;

    public GrpcMetricsController(GrpcMetricsInterceptor metrics) {
        this.metrics = metrics;
    }

    @GetMapping("/api/grpc/metrics")
    public List<GrpcMetricsInterceptor.CallMetrics> metrics() {
        return metrics.snapshot();
    }
}
