package br.com.diegobraun.protocolbench.bench.client;

import br.com.diegobraun.protocolbench.api.grpc.GetProductRequest;
import br.com.diegobraun.protocolbench.api.grpc.ListProductsRequest;
import br.com.diegobraun.protocolbench.api.grpc.Product;
import br.com.diegobraun.protocolbench.api.grpc.ProductList;
import br.com.diegobraun.protocolbench.api.grpc.ProductServiceGrpc;
import br.com.diegobraun.protocolbench.api.grpc.StreamProductsRequest;
import br.com.diegobraun.protocolbench.bench.Scenario;
import br.com.diegobraun.protocolbench.bench.Target;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;

import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class GrpcClient implements ProtocolClient {

    public enum Mode {
        BLOCKING, ASYNC, DIRECT;

        static Mode fromSystemProperty() {
            return valueOf(System.getProperty("grpc.client.mode", "direct").toUpperCase());
        }
    }

    private final Mode mode;
    private final ManagedChannel channel;
    private final ProductServiceGrpc.ProductServiceBlockingStub stub;
    private final ProductServiceGrpc.ProductServiceStub asyncStub;

    public GrpcClient(Target target) {
        this(target, Mode.fromSystemProperty());
    }

    public GrpcClient(Target target, Mode mode) {
        this.mode = mode;
        NettyChannelBuilder builder = NettyChannelBuilder.forAddress(target.host(), target.grpcPort())
                .usePlaintext()
                .maxInboundMessageSize(64 * 1024 * 1024);
        if (mode == Mode.DIRECT) {
            builder.directExecutor();
        }
        this.channel = builder.build();
        Channel intercepted = withToken(channel, System.getProperty("grpc.client.token"));
        this.stub = ProductServiceGrpc.newBlockingStub(intercepted);
        this.asyncStub = ProductServiceGrpc.newStub(intercepted);
    }

    private static Channel withToken(Channel channel, String token) {
        if (token == null || token.isBlank()) {
            return channel;
        }
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        return ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    @Override
    public String name() {
        return "grpc";
    }

    @Override
    public String label() {
        return mode == Mode.DIRECT
                ? "gRPC (HTTP/2 + Protobuf)"
                : "gRPC (HTTP/2 + Protobuf, cliente " + mode.name().toLowerCase() + ")";
    }

    @Override
    public boolean supports(Scenario scenario) {
        return true;
    }

    @Override
    public Session openSession() {
        return this::execute;
    }

    private CallResult execute(Scenario scenario, int size, long productId) throws Exception {
        ProductServiceGrpc.ProductServiceBlockingStub call = stub.withDeadlineAfter(30, TimeUnit.SECONDS);
        return switch (scenario) {
            case SINGLE -> {
                Product product = call.getProduct(GetProductRequest.newBuilder().setId(productId).build());
                yield new CallResult(1, product.getSerializedSize(), product.getName());
            }
            case LIST -> {
                ProductList list = call.listProducts(ListProductsRequest.newBuilder().setLimit(size).build());
                String firstName = list.getProductsCount() == 0 ? null : list.getProducts(0).getName();
                yield new CallResult(list.getProductsCount(), list.getSerializedSize(), firstName);
            }
            case STREAM -> mode == Mode.BLOCKING ? blockingStream(call, size) : stream(size);
        };
    }

    private static CallResult blockingStream(ProductServiceGrpc.ProductServiceBlockingStub call, int size) {
        Iterator<Product> iterator = call.streamProducts(StreamProductsRequest.newBuilder().setCount(size).build());
        int items = 0;
        long bytes = 0;
        String firstName = null;
        while (iterator.hasNext()) {
            Product product = iterator.next();
            bytes += product.getSerializedSize();
            if (items++ == 0) {
                firstName = product.getName();
            }
        }
        return new CallResult(items, bytes, firstName);
    }

    private CallResult stream(int size) throws Exception {
        CompletableFuture<CallResult> done = new CompletableFuture<>();
        asyncStub.withDeadlineAfter(30, TimeUnit.SECONDS).streamProducts(
                StreamProductsRequest.newBuilder().setCount(size).build(),
                new StreamObserver<>() {
                    private int items;
                    private long bytes;
                    private String firstName;

                    @Override
                    public void onNext(Product product) {
                        bytes += product.getSerializedSize();
                        if (items++ == 0) {
                            firstName = product.getName();
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        done.completeExceptionally(error);
                    }

                    @Override
                    public void onCompleted() {
                        done.complete(new CallResult(items, bytes, firstName));
                    }
                });
        return done.get();
    }

    @Override
    public void close() {
        channel.shutdownNow();
        try {
            channel.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
