package br.com.diegobraun.protocolbench.server.grpc;

import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

public final class GrpcMetricsInterceptor implements ServerInterceptor {

    public record CallMetrics(String method, String status, long count, double avgMs, double maxMs) {
    }

    private record Key(String method, String status) {
    }

    private static final class Stats {
        private final LongAdder count = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
        private final LongAccumulator maxNanos = new LongAccumulator(Math::max, 0);
    }

    private final ConcurrentMap<Key, Stats> stats = new ConcurrentHashMap<>();

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        long start = System.nanoTime();
        String method = call.getMethodDescriptor().getBareMethodName();
        ServerCall<ReqT, RespT> timedCall = new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
                record(method, status.getCode().name(), System.nanoTime() - start);
                super.close(status, trailers);
            }
        };
        return next.startCall(timedCall, headers);
    }

    public List<CallMetrics> snapshot() {
        return stats.entrySet().stream()
                .map(e -> new CallMetrics(
                        e.getKey().method(),
                        e.getKey().status(),
                        e.getValue().count.sum(),
                        millis((double) e.getValue().totalNanos.sum() / Math.max(1, e.getValue().count.sum())),
                        millis(e.getValue().maxNanos.get())))
                .sorted(Comparator.comparing(CallMetrics::method).thenComparing(CallMetrics::status))
                .toList();
    }

    public long count(String method, Status.Code status) {
        Stats s = stats.get(new Key(method, status.name()));
        return s == null ? 0 : s.count.sum();
    }

    private void record(String method, String status, long nanos) {
        Stats s = stats.computeIfAbsent(new Key(method, status), k -> new Stats());
        s.count.increment();
        s.totalNanos.add(nanos);
        s.maxNanos.accumulate(nanos);
    }

    private static double millis(double nanos) {
        return Math.round(nanos / 10_000.0) / 100.0;
    }
}
