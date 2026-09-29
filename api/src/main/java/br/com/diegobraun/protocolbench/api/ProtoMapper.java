package br.com.diegobraun.protocolbench.api;

public final class ProtoMapper {

    private ProtoMapper() {
    }

    public static br.com.diegobraun.protocolbench.api.grpc.Product toProto(Product product) {
        return br.com.diegobraun.protocolbench.api.grpc.Product.newBuilder()
                .setId(product.id())
                .setName(product.name())
                .setDescription(product.description())
                .setPrice(product.price())
                .setCategory(product.category())
                .addAllTags(product.tags())
                .setStock(product.stock())
                .setCreatedAt(product.createdAt())
                .build();
    }
}
