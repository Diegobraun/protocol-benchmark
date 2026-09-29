package br.com.diegobraun.protocolbench.bench;

public record BenchmarkResult(
        String protocol,
        String protocolLabel,
        String scenario,
        int size,
        int concurrency,
        long requests,
        long errors,
        double throughput,
        double throughputMin,
        double throughputMax,
        int repeats,
        double itemsPerSecond,
        double meanMs,
        double p50Ms,
        double p90Ms,
        double p99Ms,
        double maxMs,
        long avgPayloadBytes,
        String firstError) {
}
