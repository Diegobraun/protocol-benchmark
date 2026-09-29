package br.com.diegobraun.protocolbench.api;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

public final class ProductCatalog {

    public static final int SIZE = 10_000;

    private static final String[] CATEGORIES = {
            "electronics", "books", "home", "garden", "sports", "toys", "fashion", "grocery"
    };
    private static final String[] ADJECTIVES = {
            "Premium", "Compact", "Wireless", "Ergonomic", "Vintage", "Smart", "Portable", "Classic"
    };
    private static final String[] NOUNS = {
            "Speaker", "Notebook", "Lamp", "Backpack", "Kettle", "Camera", "Chair", "Watch"
    };
    private static final String[] WORDS = {
            "durable", "lightweight", "design", "quality", "everyday", "material", "comfort", "battery",
            "warranty", "modern", "reliable", "performance", "finish", "compact", "premium", "use"
    };
    private static final Instant EPOCH = Instant.parse("2024-01-01T00:00:00Z");

    private static final List<Product> PRODUCTS = IntStream.rangeClosed(1, SIZE)
            .mapToObj(ProductCatalog::generate)
            .toList();

    private ProductCatalog() {
    }

    public static Product get(long id) {
        if (id < 1 || id > SIZE) {
            throw new IllegalArgumentException("Product not found: " + id);
        }
        return PRODUCTS.get((int) id - 1);
    }

    public static List<Product> list(int limit) {
        return PRODUCTS.subList(0, clamp(limit));
    }

    public static int clamp(int limit) {
        return Math.max(0, Math.min(limit, SIZE));
    }

    private static Product generate(int id) {
        Random random = new Random(id);
        String name = pick(random, ADJECTIVES) + " " + pick(random, NOUNS) + " " + id;
        String description = String.join(" ", random.ints(24, 0, WORDS.length).mapToObj(i -> WORDS[i]).toList());
        double price = Math.round(random.nextDouble(5, 2000) * 100) / 100.0;
        List<String> tags = Arrays.asList(random.ints(3, 0, WORDS.length).mapToObj(i -> WORDS[i]).toArray(String[]::new));
        return new Product(
                id,
                name,
                description,
                price,
                pick(random, CATEGORIES),
                List.copyOf(tags),
                random.nextInt(0, 500),
                EPOCH.plus(random.nextInt(0, 600), ChronoUnit.DAYS).toString());
    }

    private static String pick(Random random, String[] values) {
        return values[random.nextInt(values.length)];
    }
}
