package br.com.diegobraun.protocolbench.bench;

import java.util.Arrays;

public enum Scenario {

    SINGLE("single", "Buscar 1 produto por id"),
    LIST("list", "Listar N produtos numa única resposta"),
    STREAM("stream", "Receber N produtos como stream de mensagens");

    private final String slug;
    private final String description;

    Scenario(String slug, String description) {
        this.slug = slug;
        this.description = description;
    }

    public String slug() {
        return slug;
    }

    public String description() {
        return description;
    }

    public static Scenario fromSlug(String slug) {
        return Arrays.stream(values())
                .filter(s -> s.slug.equalsIgnoreCase(slug))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown scenario: " + slug));
    }
}
