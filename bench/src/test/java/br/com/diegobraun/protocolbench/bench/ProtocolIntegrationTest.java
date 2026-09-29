package br.com.diegobraun.protocolbench.bench;

import br.com.diegobraun.protocolbench.api.ProductCatalog;
import br.com.diegobraun.protocolbench.bench.client.CallResult;
import br.com.diegobraun.protocolbench.bench.client.Clients;
import br.com.diegobraun.protocolbench.bench.client.ProtocolClient;
import br.com.diegobraun.protocolbench.bench.report.ReportWriter;
import br.com.diegobraun.protocolbench.server.ServerApplication;
import br.com.diegobraun.protocolbench.server.grpc.GrpcServerLifecycle;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtocolIntegrationTest {

    private static ConfigurableApplicationContext server;
    private static Target target;

    @BeforeAll
    static void startServer() {
        server = new SpringApplicationBuilder(ServerApplication.class)
                .run("--server.port=0", "--grpc.port=0");
        int httpPort = Integer.parseInt(server.getEnvironment().getProperty("local.server.port"));
        int grpcPort = server.getBean(GrpcServerLifecycle.class).port();
        target = new Target("localhost", httpPort, grpcPort);
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @ParameterizedTest
    @EnumSource(Scenario.class)
    void everyProtocolReturnsTheSameData(Scenario scenario) throws Exception {
        String expectedFirst = scenario == Scenario.SINGLE ? ProductCatalog.get(42).name() : ProductCatalog.get(1).name();
        int size = scenario == Scenario.SINGLE ? 1 : 50;

        for (String protocol : Clients.ALL) {
            try (ProtocolClient client = Clients.create(protocol, target);
                 ProtocolClient.Session session = client.openSession()) {
                if (!client.supports(scenario)) {
                    continue;
                }
                CallResult result = session.execute(scenario, size, 42);
                assertThat(result.items()).as(protocol).isEqualTo(size);
                assertThat(result.firstName()).as(protocol).isEqualTo(expectedFirst);
                assertThat(result.payloadBytes()).as(protocol).isPositive();
            }
        }
    }

    @Test
    void protobufPayloadIsSmallerThanJson() throws Exception {
        try (ProtocolClient rest = Clients.create("rest", target);
             ProtocolClient grpc = Clients.create("grpc", target)) {
            long json = rest.openSession().execute(Scenario.LIST, 100, 1).payloadBytes();
            long protobuf = grpc.openSession().execute(Scenario.LIST, 100, 1).payloadBytes();
            assertThat(protobuf).isLessThan(json);
        }
    }

    @Test
    void graphqlDoesNotSupportStreaming() throws Exception {
        try (ProtocolClient client = Clients.create("graphql", target)) {
            assertThat(client.supports(Scenario.STREAM)).isFalse();
            assertThatThrownBy(() -> client.openSession().execute(Scenario.STREAM, 10, 1))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    void runnerProducesResultsWithoutErrorsAndWritesReports(@TempDir Path output) throws Exception {
        BenchmarkConfig config = new BenchmarkConfig(
                target,
                Clients.ALL,
                List.of(Scenario.values()),
                List.of(2),
                Duration.ofMillis(200),
                Duration.ofMillis(300),
                2,
                20,
                50,
                output);

        List<BenchmarkResult> results = new BenchmarkRunner(config, line -> { }).run();

        assertThat(results).hasSize(11);
        assertThat(results).allSatisfy(r -> {
            assertThat(r.errors()).as(r.protocol() + "/" + r.scenario() + ": " + r.firstError()).isZero();
            assertThat(r.requests()).isPositive();
            assertThat(r.p99Ms()).isGreaterThanOrEqualTo(r.p50Ms());
            assertThat(r.repeats()).isEqualTo(2);
            assertThat(r.throughputMin()).isLessThanOrEqualTo(r.throughput());
            assertThat(r.throughputMax()).isGreaterThanOrEqualTo(r.throughput());
        });

        ReportWriter writer = new ReportWriter();
        writer.write(writer.build(config, results), output);
        assertThat(output.resolve("results.json")).exists();
        assertThat(Files.readString(output.resolve("results.md"))).contains("| grpc |");
        assertThat(Files.readString(output.resolve("report.html"))).doesNotContain("__BENCHMARK_DATA__");
    }
}
