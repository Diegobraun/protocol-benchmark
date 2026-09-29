package br.com.diegobraun.protocolbench.bench.client;

import br.com.diegobraun.protocolbench.bench.Scenario;

public interface ProtocolClient extends AutoCloseable {

    String name();

    String label();

    boolean supports(Scenario scenario);

    Session openSession() throws Exception;

    @Override
    default void close() {
    }

    interface Session extends AutoCloseable {

        CallResult execute(Scenario scenario, int size, long productId) throws Exception;

        @Override
        default void close() {
        }
    }
}
