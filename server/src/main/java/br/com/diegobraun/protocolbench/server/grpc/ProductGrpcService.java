package br.com.diegobraun.protocolbench.server.grpc;

import br.com.diegobraun.protocolbench.api.ProductCatalog;
import br.com.diegobraun.protocolbench.api.ProtoMapper;
import br.com.diegobraun.protocolbench.api.grpc.GetProductRequest;
import br.com.diegobraun.protocolbench.api.grpc.ListProductsRequest;
import br.com.diegobraun.protocolbench.api.grpc.PriceQuote;
import br.com.diegobraun.protocolbench.api.grpc.PriceQuoteRequest;
import br.com.diegobraun.protocolbench.api.grpc.Product;
import br.com.diegobraun.protocolbench.api.grpc.ProductList;
import br.com.diegobraun.protocolbench.api.grpc.ProductServiceGrpc;
import br.com.diegobraun.protocolbench.api.grpc.StreamProductsRequest;
import br.com.diegobraun.protocolbench.api.grpc.UploadSummary;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.List;

public class ProductGrpcService extends ProductServiceGrpc.ProductServiceImplBase {

    private final List<Product> products = ProductCatalog.list(ProductCatalog.SIZE).stream()
            .map(ProtoMapper::toProto)
            .toList();

    @Override
    public void getProduct(GetProductRequest request, StreamObserver<Product> responseObserver) {
        long id = request.getId();
        if (id < 1 || id > products.size()) {
            responseObserver.onError(Status.NOT_FOUND.withDescription("Product not found: " + id).asRuntimeException());
            return;
        }
        responseObserver.onNext(products.get((int) id - 1));
        responseObserver.onCompleted();
    }

    @Override
    public void listProducts(ListProductsRequest request, StreamObserver<ProductList> responseObserver) {
        responseObserver.onNext(ProductList.newBuilder()
                .addAllProducts(products.subList(0, ProductCatalog.clamp(request.getLimit())))
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public StreamObserver<Product> uploadProducts(StreamObserver<UploadSummary> responseObserver) {
        String uploadedBy = GrpcAuthInterceptor.CLIENT_NAME.get();
        return new StreamObserver<>() {
            private int accepted;
            private int rejected;
            private double totalValue;

            @Override
            public void onNext(Product product) {
                if (product.getName().isBlank() || product.getPrice() <= 0) {
                    rejected++;
                    return;
                }
                accepted++;
                totalValue += product.getPrice() * product.getStock();
            }

            @Override
            public void onError(Throwable error) {
            }

            @Override
            public void onCompleted() {
                responseObserver.onNext(UploadSummary.newBuilder()
                        .setAccepted(accepted)
                        .setRejected(rejected)
                        .setTotalValue(round(totalValue))
                        .setUploadedBy(uploadedBy == null ? "anonymous" : uploadedBy)
                        .build());
                responseObserver.onCompleted();
            }
        };
    }

    @Override
    public StreamObserver<PriceQuoteRequest> quotePrices(StreamObserver<PriceQuote> responseObserver) {
        return new StreamObserver<>() {
            private boolean failed;

            @Override
            public void onNext(PriceQuoteRequest request) {
                if (failed) {
                    return;
                }
                if (request.getQuantity() <= 0) {
                    fail(Status.INVALID_ARGUMENT.withDescription("quantity must be positive"));
                    return;
                }
                long id = request.getProductId();
                if (id < 1 || id > products.size()) {
                    fail(Status.NOT_FOUND.withDescription("Product not found: " + id));
                    return;
                }
                Product product = products.get((int) id - 1);
                responseObserver.onNext(PriceQuote.newBuilder()
                        .setProductId(id)
                        .setQuantity(request.getQuantity())
                        .setUnitPrice(product.getPrice())
                        .setTotal(round(product.getPrice() * request.getQuantity()))
                        .setInStock(product.getStock() >= request.getQuantity())
                        .build());
            }

            @Override
            public void onError(Throwable error) {
            }

            @Override
            public void onCompleted() {
                if (!failed) {
                    responseObserver.onCompleted();
                }
            }

            private void fail(Status status) {
                failed = true;
                responseObserver.onError(status.asRuntimeException());
            }
        };
    }

    @Override
    public void streamProducts(StreamProductsRequest request, StreamObserver<Product> responseObserver) {
        for (Product product : products.subList(0, ProductCatalog.clamp(request.getCount()))) {
            responseObserver.onNext(product);
        }
        responseObserver.onCompleted();
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
