package br.com.diegobraun.protocolbench.server.rest;

import br.com.diegobraun.protocolbench.api.Product;
import br.com.diegobraun.protocolbench.api.ProductCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductRestController {

    private static final byte[] DATA_PREFIX = "data: ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EVENT_SUFFIX = "\n\n".getBytes(StandardCharsets.UTF_8);

    private final ObjectMapper objectMapper;

    public ProductRestController(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @GetMapping("/{id}")
    public Product get(@PathVariable long id) {
        return ProductCatalog.get(id);
    }

    @GetMapping
    public List<Product> list(@RequestParam(defaultValue = "100") int limit) {
        return ProductCatalog.list(limit);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> stream(@RequestParam(defaultValue = "100") int count) {
        List<Product> products = ProductCatalog.list(count);
        StreamingResponseBody body = out -> {
            for (Product product : products) {
                out.write(DATA_PREFIX);
                out.write(objectMapper.writeValueAsBytes(product));
                out.write(EVENT_SUFFIX);
                out.flush();
            }
        };
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(body);
    }
}
