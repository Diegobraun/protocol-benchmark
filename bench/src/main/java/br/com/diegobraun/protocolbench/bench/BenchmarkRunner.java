package br.com.diegobraun.protocolbench.bench;

import br.com.diegobraun.protocolbench.api.ProductCatalog;
import br.com.diegobraun.protocolbench.bench.client.CallResult;
import br.com.diegobraun.protocolbench.bench.client.Clients;
import br.com.diegobraun.protocolbench.bench.client.ProtocolClient;
import org.HdrHistogram.Histogram;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class BenchmarkRunner {

    private static final long HIGHEST_TRACKABLE_MICROS = TimeUnit.SECONDS.toMicros(60);

    private final BenchmarkConfig config;
    private final Consumer<String> log;

    public BenchmarkRunner(BenchmarkConfig config, Consumer<String> log) {
        this.config = config;
        this.log = log;
    }

    public List<BenchmarkResult> run() throws Exception {
        List<BenchmarkResult> results = new ArrayList<>();
        for (Scenario scenario : config.scenarios()) {
            for (String protocol : config.protocols()) {
                try (ProtocolClient client = Clients.create(protocol, config.target())) {
                    if (!client.supports(scenario)) {
                        log.accept(String.format("%-7s %-10s não suportado, pulando", scenario.slug(), protocol));
                        continue;
                    }
                    for (int concurrency : config.concurrencies()) {
                        BenchmarkResult result = runOne(client, scenario, concurrency);
                        results.add(result);
                        log.accept(String.format("%-7s %-10s c=%-4d %10.0f req/s (%.0f–%.0f)   p50 %8.2f ms   p99 %8.2f ms   erros %d",
                                scenario.slug(), protocol, concurrency, result.throughput(),
                                result.throughputMin(), result.throughputMax(),
                                result.p50Ms(), result.p99Ms(), result.errors()));
                    }
                }
            }
        }
        return results;
    }

    BenchmarkResult runOne(ProtocolClient client, Scenario scenario, int concurrency) throws Exception {
        int size = config.sizeFor(scenario);
        List<ProtocolClient.Session> sessions = new ArrayList<>(concurrency);
        try {
            for (int i = 0; i < concurrency; i++) {
                sessions.add(client.openSession());
            }
            runPhase(sessions, scenario, size, config.warmup());
            List<BenchmarkResult> runs = new ArrayList<>();
            for (int i = 0; i < config.repeats(); i++) {
                long start = System.nanoTime();
                List<WorkerStats> stats = runPhase(sessions, scenario, size, config.duration());
                double elapsedSeconds = (System.nanoTime() - start) / 1e9;
                runs.add(summarize(client, scenario, size, concurrency, stats, elapsedSeconds));
            }
            return median(runs);
        } finally {
            sessions.forEach(BenchmarkRunner::closeQuietly);
        }
    }

    private List<WorkerStats> runPhase(List<ProtocolClient.Session> sessions, Scenario scenario, int size,
                                       Duration duration) throws Exception {
        long deadline = System.nanoTime() + duration.toNanos();
        List<WorkerStats> stats = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<WorkerStats>> futures = sessions.stream()
                    .map(session -> executor.submit(() -> work(session, scenario, size, deadline)))
                    .toList();
            for (Future<WorkerStats> future : futures) {
                stats.add(future.get());
            }
        }
        return stats;
    }

    private static WorkerStats work(ProtocolClient.Session session, Scenario scenario, int size, long deadline) {
        WorkerStats stats = new WorkerStats();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        while (System.nanoTime() < deadline) {
            long productId = random.nextLong(1, ProductCatalog.SIZE + 1);
            long start = System.nanoTime();
            try {
                CallResult result = session.execute(scenario, size, productId);
                long micros = (System.nanoTime() - start) / 1_000;
                if (result.items() != size) {
                    stats.error("expected " + size + " items, got " + result.items());
                    continue;
                }
                stats.histogram.recordValue(Math.min(Math.max(micros, 1), HIGHEST_TRACKABLE_MICROS));
                stats.requests++;
                stats.items += result.items();
                stats.bytes += result.payloadBytes();
            } catch (Exception e) {
                stats.error(e.toString());
                sleepQuietly();
            }
        }
        return stats;
    }

    static BenchmarkResult median(List<BenchmarkResult> runs) {
        List<BenchmarkResult> sorted = runs.stream()
                .sorted(Comparator.comparingDouble(BenchmarkResult::throughput))
                .toList();
        BenchmarkResult median = sorted.get(sorted.size() / 2);
        long errors = runs.stream().mapToLong(BenchmarkResult::errors).sum();
        String firstError = runs.stream().map(BenchmarkResult::firstError).filter(Objects::nonNull).findFirst().orElse(null);
        return new BenchmarkResult(
                median.protocol(), median.protocolLabel(), median.scenario(), median.size(), median.concurrency(),
                median.requests(), errors,
                median.throughput(), sorted.getFirst().throughput(), sorted.getLast().throughput(), runs.size(),
                median.itemsPerSecond(), median.meanMs(), median.p50Ms(), median.p90Ms(), median.p99Ms(), median.maxMs(),
                median.avgPayloadBytes(), firstError);
    }

    private static BenchmarkResult summarize(ProtocolClient client, Scenario scenario, int size, int concurrency,
                                             List<WorkerStats> stats, double elapsedSeconds) {
        Histogram histogram = new Histogram(HIGHEST_TRACKABLE_MICROS, 3);
        long requests = 0;
        long errors = 0;
        long items = 0;
        long bytes = 0;
        String firstError = null;
        for (WorkerStats worker : stats) {
            histogram.add(worker.histogram);
            requests += worker.requests;
            errors += worker.errors;
            items += worker.items;
            bytes += worker.bytes;
            if (firstError == null) {
                firstError = worker.firstError;
            }
        }
        return new BenchmarkResult(
                client.name(),
                client.label(),
                scenario.slug(),
                size,
                concurrency,
                requests,
                errors,
                requests / elapsedSeconds,
                requests / elapsedSeconds,
                requests / elapsedSeconds,
                1,
                items / elapsedSeconds,
                millis(histogram.getMean()),
                millis(histogram.getValueAtPercentile(50)),
                millis(histogram.getValueAtPercentile(90)),
                millis(histogram.getValueAtPercentile(99)),
                millis(histogram.getMaxValue()),
                requests == 0 ? 0 : bytes / requests,
                firstError);
    }

    private static double millis(double micros) {
        return Math.round(micros / 10.0) / 100.0;
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(ProtocolClient.Session session) {
        try {
            session.close();
        } catch (Exception ignored) {
        }
    }

    private static final class WorkerStats {
        private final Histogram histogram = new Histogram(HIGHEST_TRACKABLE_MICROS, 3);
        private long requests;
        private long errors;
        private long items;
        private long bytes;
        private String firstError;

        private void error(String message) {
            errors++;
            if (firstError == null) {
                firstError = message;
            }
        }
    }
}
