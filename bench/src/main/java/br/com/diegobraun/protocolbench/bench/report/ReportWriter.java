package br.com.diegobraun.protocolbench.bench.report;

import br.com.diegobraun.protocolbench.bench.BenchmarkConfig;
import br.com.diegobraun.protocolbench.bench.BenchmarkResult;
import br.com.diegobraun.protocolbench.bench.Scenario;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ReportWriter {

    private static final String DATA_PLACEHOLDER = "__BENCHMARK_DATA__";

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public record Report(String generatedAt, Map<String, Object> environment, Map<String, Object> config,
                         List<BenchmarkResult> results) {
    }

    public Report build(BenchmarkConfig config, List<BenchmarkResult> results) {
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("java", System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        environment.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        environment.put("cpus", Runtime.getRuntime().availableProcessors());
        environment.put("loadAverage", Math.round(ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage() * 10) / 10.0);

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("target", config.target().host());
        settings.put("warmup", config.warmup().toString());
        settings.put("duration", config.duration().toString());
        settings.put("repeats", config.repeats());
        settings.put("listSize", config.listSize());
        settings.put("streamSize", config.streamSize());
        settings.put("concurrencies", config.concurrencies());

        return new Report(Instant.now().toString(), environment, settings, results);
    }

    public void write(Report report, Path directory) throws IOException {
        Files.createDirectories(directory);
        String json = mapper.writeValueAsString(report);
        Files.writeString(directory.resolve("results.json"), json);
        Files.writeString(directory.resolve("results.md"), markdown(report));
        Files.writeString(directory.resolve("report.html"), template().replace(DATA_PLACEHOLDER, json));
    }

    public String markdown(Report report) {
        StringBuilder md = new StringBuilder();
        md.append("# Resultados\n\n");
        md.append("Gerado em ").append(report.generatedAt()).append(" · ")
                .append(report.environment().get("java")).append(" · ")
                .append(report.environment().get("os")).append(" · ")
                .append(report.environment().get("cpus")).append(" CPUs · load average ")
                .append(report.environment().get("loadAverage")).append(" no início\n\n");
        md.append("Cada linha é a mediana de ").append(report.config().get("repeats"))
                .append(" medições. Variação = (maior − menor throughput) / mediana.\n\n");
        for (Scenario scenario : Scenario.values()) {
            List<BenchmarkResult> rows = report.results().stream()
                    .filter(r -> r.scenario().equals(scenario.slug()))
                    .toList();
            if (rows.isEmpty()) {
                continue;
            }
            md.append("## ").append(scenario.slug()).append(" — ").append(scenario.description())
                    .append(" (N = ").append(rows.getFirst().size()).append(")\n\n");
            md.append("| Protocolo | Concorrência | req/s | variação | itens/s | p50 (ms) | p90 (ms) | p99 (ms) | max (ms) | payload | erros |\n");
            md.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
            for (BenchmarkResult r : rows) {
                md.append(String.format(Locale.ROOT, "| %s | %d | %,.0f | %s | %,.0f | %.2f | %.2f | %.2f | %.2f | %s | %d |%n",
                        r.protocol(), r.concurrency(), r.throughput(), spread(r), r.itemsPerSecond(),
                        r.p50Ms(), r.p90Ms(), r.p99Ms(), r.maxMs(), humanBytes(r.avgPayloadBytes()), r.errors()));
            }
            md.append('\n');
        }
        return md.toString();
    }

    static String spread(BenchmarkResult r) {
        if (r.repeats() < 2 || r.throughput() == 0) {
            return "–";
        }
        return String.format(Locale.ROOT, "±%.0f%%", 50 * (r.throughputMax() - r.throughputMin()) / r.throughput());
    }

    static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String template() {
        try (InputStream in = ReportWriter.class.getResourceAsStream("/report-template.html")) {
            if (in == null) {
                throw new IllegalStateException("report-template.html not found on classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
