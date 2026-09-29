package br.com.diegobraun.protocolbench.api;

import java.util.List;

public record Product(
        long id,
        String name,
        String description,
        double price,
        String category,
        List<String> tags,
        int stock,
        String createdAt) {
}
