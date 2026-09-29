package br.com.diegobraun.protocolbench.server.grpc;

import br.com.diegobraun.protocolbench.api.ProductCatalog;
import br.com.diegobraun.protocolbench.api.ProtoMapper;
import br.com.diegobraun.protocolbench.api.grpc.GetProductRequest;
import br.com.diegobraun.protocolbench.api.grpc.ListProductsRequest;
import br.com.diegobraun.protocolbench.api.grpc.Product;
import br.com.diegobraun.protocolbench.api.grpc.ProductList;
import br.com.diegobraun.protocolbench.api.grpc.ProductServiceGrpc;
import br.com.diegobraun.protocolbench.api.grpc.StreamProductsRequest;
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
    public void streamProducts(StreamProductsRequest request, StreamObserver<Product> responseObserver) {
        for (Product product : products.subList(0, ProductCatalog.clamp(request.getCount()))) {
            responseObserver.onNext(product);
        }
        responseObserver.onCompleted();
    }
}
