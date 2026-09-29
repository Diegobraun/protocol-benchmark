package br.com.diegobraun.protocolbench.bench;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public record BenchmarkConfig(
        Target target,
        List<String> protocols,
        List<Scenario> scenarios,
        List<Integer> concurrencies,
        Duration warmup,
        Duration duration,
        int repeats,
        int listSize,
        int streamSize,
        Path outputDir) {

    public int sizeFor(Scenario scenario) {
        return switch (scenario) {
            case SINGLE -> 1;
            case LIST -> listSize;
            case STREAM -> streamSize;
        };
    }
}
