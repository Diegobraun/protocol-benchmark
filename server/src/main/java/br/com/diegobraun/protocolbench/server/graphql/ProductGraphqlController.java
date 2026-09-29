package br.com.diegobraun.protocolbench.server.graphql;

import br.com.diegobraun.protocolbench.api.Product;
import br.com.diegobraun.protocolbench.api.ProductCatalog;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;

@Controller
public class ProductGraphqlController {

    @QueryMapping
    public Product product(@Argument int id) {
        return ProductCatalog.get(id);
    }

    @QueryMapping
    public List<Product> products(@Argument int limit) {
        return ProductCatalog.list(limit);
    }
}
