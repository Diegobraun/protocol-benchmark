package br.com.diegobraun.protocolbench.server.grpc;

import br.com.diegobraun.protocolbench.api.ProductCatalog;
import br.com.diegobraun.protocolbench.api.grpc.GetProductRequest;
import br.com.diegobraun.protocolbench.api.grpc.PriceQuote;
import br.com.diegobraun.protocolbench.api.grpc.PriceQuoteRequest;
import br.com.diegobraun.protocolbench.api.grpc.Product;
import br.com.diegobraun.protocolbench.api.grpc.ProductServiceGrpc;
import br.com.diegobraun.protocolbench.api.grpc.UploadSummary;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ProductGrpcServiceTest {

    private static final Map<String, String> CLIENTS = Map.of("inventory-service", "secret-token");

    private final GrpcMetricsInterceptor metrics = new GrpcMetricsInterceptor();
    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void shutdown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void clientStreamingAggregatesEveryUploadedProduct() throws Exception {
        start(CLIENTS);
        CompletableFuture<UploadSummary> summary = new CompletableFuture<>();
        StreamObserver<Product> upload = authenticatedAsyncStub().uploadProducts(observerOf(summary));

        upload.onNext(product(1, "Lamp", 10.0, 3));
        upload.onNext(product(2, "Chair", 25.5, 2));
        upload.onNext(product(3, "", 99.0, 1));
        upload.onNext(product(4, "Broken", 0, 5));
        upload.onCompleted();

        UploadSummary result = summary.get(5, TimeUnit.SECONDS);
        assertThat(result.getAccepted()).isEqualTo(2);
        assertThat(result.getRejected()).isEqualTo(2);
        assertThat(result.getTotalValue()).isCloseTo(81.0, within(0.001));
        assertThat(result.getUploadedBy()).isEqualTo("inventory-service");
    }

    @Test
    void bidirectionalStreamingAnswersEachRequestBeforeTheNextIsSent() throws Exception {
        start(Map.of());
        BlockingQueue<PriceQuote> quotes = new LinkedBlockingQueue<>();
        CompletableFuture<Void> completed = new CompletableFuture<>();
        StreamObserver<PriceQuoteRequest> requests = ProductServiceGrpc.newStub(channel)
                .quotePrices(new StreamObserver<>() {
                    @Override
                    public void onNext(PriceQuote quote) {
                        quotes.add(quote);
                    }

                    @Override
                    public void onError(Throwable error) {
                        completed.completeExceptionally(error);
                    }

                    @Override
                    public void onCompleted() {
                        completed.complete(null);
                    }
                });

        for (long id : List.of(7L, 42L, 1000L)) {
            requests.onNext(PriceQuoteRequest.newBuilder().setProductId(id).setQuantity(3).build());
            PriceQuote quote = quotes.poll(5, TimeUnit.SECONDS);
            assertThat(quote).isNotNull();
            assertThat(quote.getProductId()).isEqualTo(id);
            assertThat(quote.getUnitPrice()).isEqualTo(ProductCatalog.get(id).price());
            assertThat(quote.getTotal()).isCloseTo(ProductCatalog.get(id).price() * 3, within(0.01));
        }
        requests.onCompleted();

        completed.get(5, TimeUnit.SECONDS);
        assertThat(quotes).isEmpty();
    }

    @Test
    void bidirectionalStreamingFailsTheCallOnInvalidQuantity() {
        start(Map.of());
        CompletableFuture<Void> completed = new CompletableFuture<>();
        StreamObserver<PriceQuoteRequest> requests = ProductServiceGrpc.newStub(channel)
                .quotePrices(new StreamObserver<>() {
                    @Override
                    public void onNext(PriceQuote quote) {
                    }

                    @Override
                    public void onError(Throwable error) {
                        completed.completeExceptionally(error);
                    }

                    @Override
                    public void onCompleted() {
                        completed.complete(null);
                    }
                });

        requests.onNext(PriceQuoteRequest.newBuilder().setProductId(1).setQuantity(0).build());

        assertThatThrownBy(() -> completed.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .satisfies(e -> assertThat(Status.fromThrowable(e).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    void rejectsCallsWithoutValidTokenWhenAuthIsEnabled() {
        start(CLIENTS);
        GetProductRequest request = GetProductRequest.newBuilder().setId(1).build();

        assertThatThrownBy(() -> ProductServiceGrpc.newBlockingStub(channel).getProduct(request))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));

        assertThatThrownBy(() -> ProductServiceGrpc.newBlockingStub(channel)
                .withInterceptors(bearer("wrong-token"))
                .getProduct(request))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));

        Product product = ProductServiceGrpc.newBlockingStub(channel)
                .withInterceptors(bearer("secret-token"))
                .getProduct(request);
        assertThat(product.getName()).isEqualTo(ProductCatalog.get(1).name());
    }

    @Test
    void allowsAnonymousCallsWhenNoClientIsConfigured() {
        start(Map.of());
        Product product = ProductServiceGrpc.newBlockingStub(channel)
                .getProduct(GetProductRequest.newBuilder().setId(5).build());
        assertThat(product.getId()).isEqualTo(5);
    }

    @Test
    void metricsCountCallsPerMethodAndStatusIncludingRejectedOnes() {
        start(CLIENTS);
        ProductServiceGrpc.ProductServiceBlockingStub authenticated =
                ProductServiceGrpc.newBlockingStub(channel).withInterceptors(bearer("secret-token"));

        authenticated.getProduct(GetProductRequest.newBuilder().setId(1).build());
        authenticated.getProduct(GetProductRequest.newBuilder().setId(2).build());
        assertThatThrownBy(() -> authenticated.getProduct(GetProductRequest.newBuilder().setId(999_999).build()))
                .isInstanceOf(StatusRuntimeException.class);
        assertThatThrownBy(() -> ProductServiceGrpc.newBlockingStub(channel)
                .getProduct(GetProductRequest.newBuilder().setId(1).build()))
                .isInstanceOf(StatusRuntimeException.class);

        assertThat(metrics.count("GetProduct", Status.Code.OK)).isEqualTo(2);
        assertThat(metrics.count("GetProduct", Status.Code.NOT_FOUND)).isEqualTo(1);
        assertThat(metrics.count("GetProduct", Status.Code.UNAUTHENTICATED)).isEqualTo(1);
        assertThat(metrics.snapshot()).allSatisfy(m -> assertThat(m.avgMs()).isLessThanOrEqualTo(m.maxMs()));
    }

    private void start(Map<String, String> clients) {
        String name = InProcessServerBuilder.generateName();
        try {
            server = InProcessServerBuilder.forName(name)
                    .directExecutor()
                    .addService(ServerInterceptors.intercept(
                            new ProductGrpcService(), new GrpcAuthInterceptor(clients), metrics))
                    .build()
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    private ProductServiceGrpc.ProductServiceStub authenticatedAsyncStub() {
        return ProductServiceGrpc.newStub(channel).withInterceptors(bearer("secret-token"));
    }

    private static io.grpc.ClientInterceptor bearer(String token) {
        Metadata metadata = new Metadata();
        metadata.put(GrpcAuthInterceptor.AUTHORIZATION, "Bearer " + token);
        return MetadataUtils.newAttachHeadersInterceptor(metadata);
    }

    private static Product product(long id, String name, double price, int stock) {
        return Product.newBuilder().setId(id).setName(name).setPrice(price).setStock(stock).build();
    }

    private static <T> StreamObserver<T> observerOf(CompletableFuture<T> future) {
        return new StreamObserver<>() {
            @Override
            public void onNext(T value) {
                future.complete(value);
            }

            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);
            }

            @Override
            public void onCompleted() {
            }
        };
    }
}
