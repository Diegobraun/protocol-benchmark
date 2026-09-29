package br.com.diegobraun.protocolbench.bench;

import br.com.diegobraun.protocolbench.bench.client.Clients;
import br.com.diegobraun.protocolbench.bench.report.ReportWriter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class BenchmarkMain {

    private static final String USAGE = """
            Uso: java -jar bench/target/bench.jar [opções]

              --host=localhost             host do servidor
              --http-port=8080             porta HTTP (REST, GraphQL, WebSocket)
              --grpc-port=9090             porta gRPC
              --protocols=rest,graphql,grpc,websocket
              --scenarios=single,list,stream
              --concurrency=1,16,64        workers simultâneos (lista)
              --warmup=5s                  aquecimento por rodada
              --duration=5s                duração de cada medição
              --repeat=3                   medições por combinação (usa a mediana)
              --list-size=100              itens no cenário list
              --stream-size=1000           itens no cenário stream
              --out=results                diretório dos relatórios
            """;

    private BenchmarkMain() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        if (options.containsKey("help")) {
            System.out.println(USAGE);
            return;
        }

        BenchmarkConfig config = new BenchmarkConfig(
                new Target(
                        options.getOrDefault("host", "localhost"),
                        Integer.parseInt(options.getOrDefault("http-port", "8080")),
                        Integer.parseInt(options.getOrDefault("grpc-port", "9090"))),
                list(options.getOrDefault("protocols", String.join(",", Clients.ALL))),
                list(options.getOrDefault("scenarios", "single,list,stream")).stream().map(Scenario::fromSlug).toList(),
                list(options.getOrDefault("concurrency", "1,16,64")).stream().map(Integer::parseInt).toList(),
                duration(options.getOrDefault("warmup", "5s")),
                duration(options.getOrDefault("duration", "5s")),
                Integer.parseInt(options.getOrDefault("repeat", "3")),
                Integer.parseInt(options.getOrDefault("list-size", "100")),
                Integer.parseInt(options.getOrDefault("stream-size", "1000")),
                Path.of(options.getOrDefault("out", "results")));

        requireReachable(config.target().host(), config.target().httpPort());
        requireReachable(config.target().host(), config.target().grpcPort());

        System.out.printf("Benchmark: %s × %s × concorrência %s (warmup %s, %d × %s de medição)%n%n",
                config.scenarios(), config.protocols(), config.concurrencies(), config.warmup(), config.repeats(), config.duration());

        List<BenchmarkResult> results = new BenchmarkRunner(config, System.out::println).run();

        ReportWriter writer = new ReportWriter();
        ReportWriter.Report report = writer.build(config, results);
        writer.write(report, config.outputDir());

        System.out.println();
        System.out.println(writer.markdown(report));
        System.out.println("Relatórios em " + config.outputDir().toAbsolutePath() + " (report.html, results.md, results.json)");
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Opção inválida: " + arg + "\n\n" + USAGE);
            }
            String[] parts = arg.substring(2).split("=", 2);
            options.put(parts[0], parts.length > 1 ? parts[1] : "true");
        }
        return options;
    }

    static Duration duration(String value) {
        String v = value.trim().toLowerCase();
        if (v.endsWith("ms")) {
            return Duration.ofMillis(Long.parseLong(v.substring(0, v.length() - 2)));
        }
        if (v.endsWith("s")) {
            return Duration.ofSeconds(Long.parseLong(v.substring(0, v.length() - 1)));
        }
        if (v.endsWith("m")) {
            return Duration.ofMinutes(Long.parseLong(v.substring(0, v.length() - 1)));
        }
        return Duration.ofSeconds(Long.parseLong(v));
    }

    private static List<String> list(String value) {
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static void requireReachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2_000);
        } catch (IOException e) {
            System.err.printf("Servidor inacessível em %s:%d. Suba o servidor antes: java -jar server/target/server-1.0.0-exec.jar%n", host, port);
            System.exit(1);
        }
    }
}
