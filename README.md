# Protocol Benchmark

A mesma API de catálogo de produtos implementada em **REST**, **GraphQL**, **gRPC**, **WebSocket** e **SSE**, mais um gerador de carga que mede throughput, latência (p50/p90/p99) e tamanho de payload de cada protocolo sob os mesmos cenários.

![Relatório do benchmark](docs/report.png)

## O que é comparado

| Protocolo | Transporte | Serialização | Conexões do cliente | Implementação no servidor |
|---|---|---|---|---|
| REST | HTTP/1.1 | JSON (Jackson) | Pool do `java.net.http.HttpClient` | Spring MVC em virtual threads |
| GraphQL | HTTP/1.1 (`POST /graphql`) | JSON | Pool do `HttpClient` | Spring for GraphQL |
| gRPC | HTTP/2 | Protobuf | 1 canal multiplexado, stub assíncrono no stream | grpc-java (Netty) |
| WebSocket | TCP após upgrade HTTP | JSON em frames de texto | 1 conexão por worker | Spring WebSocket (Tomcat) |
| SSE | HTTP/1.1 chunked | JSON por evento | Pool do `HttpClient` | Spring MVC `StreamingResponseBody` |

Três cenários, todos com os mesmos dados (um catálogo determinístico de 10.000 produtos com ~380 bytes cada em JSON):

| Cenário | O que faz | REST | GraphQL | gRPC | WebSocket |
|---|---|---|---|---|---|
| `single` | Busca 1 produto por id aleatório | `GET /api/products/{id}` | `query { product(id) }` | unary `GetProduct` | mensagem `get` |
| `list` | Retorna N produtos numa única resposta | `GET /api/products?limit=N` | `query { products(limit) }` | unary `ListProducts` | mensagem `list` |
| `stream` | Envia N produtos como mensagens separadas | SSE `GET /api/products/stream` | não suportado* | server streaming `StreamProducts` | N mensagens + `done` |

\* Streaming em GraphQL exige subscriptions sobre WebSocket (`graphql-ws`), que é outro protocolo por baixo. Ficou fora para não misturar as coisas.

## Como rodar

Requisitos: JDK 21+.

```bash
./run-benchmark.sh
```

O script compila o projeto se necessário, sobe o servidor numa JVM separada, roda o benchmark completo e grava em `results/`:

- `report.html`: gráficos de throughput, latência p99 e payload por cenário
- `results.md`: tabelas em Markdown
- `results.json`: dados brutos

Todas as opções do gerador de carga:

```bash
./run-benchmark.sh --help

./run-benchmark.sh --protocols=grpc,rest --scenarios=list --concurrency=1,8,32,128 --duration=20s
```

| Opção | Padrão | Descrição |
|---|---|---|
| `--protocols` | `rest,graphql,grpc,websocket` | Protocolos a testar |
| `--scenarios` | `single,list,stream` | Cenários |
| `--concurrency` | `1,16,64` | Número de workers simultâneos (cada valor é uma rodada) |
| `--warmup` | `5s` | Aquecimento da JIT antes de medir |
| `--duration` | `5s` | Duração de cada medição |
| `--repeat` | `3` | Medições por combinação; o relatório usa a mediana |
| `--list-size` | `100` | Itens no cenário `list` |
| `--stream-size` | `1000` | Itens no cenário `stream` |
| `--host`, `--http-port`, `--grpc-port` | `localhost`, `8080`, `9090` | Onde está o servidor |

Para rodar servidor e cliente em máquinas diferentes (o que dá números mais realistas):

```bash
java -jar server/target/server-1.0.0-exec.jar                       # máquina A
java -jar bench/target/bench.jar --host=maquina-a --duration=30s    # máquina B
```

Os testes sobem o servidor em portas aleatórias e verificam que todos os protocolos devolvem exatamente os mesmos dados:

```bash
./mvnw verify
```

## Resultados

Rodada completa com as opções padrão (concorrência 1/16/64, warmup de 5 s, mediana de 3 medições de 5 s), cliente e servidor no mesmo MacBook (Apple Silicon, 10 CPUs, JDK 25). Relatório interativo em [`results/report.html`](results/report.html) e dados brutos em [`results/results.json`](results/results.json).

A coluna **variação** mostra a dispersão entre as 3 medições de cada linha. Nesta rodada ficou em ±6% ou menos em todos os casos. Numa rodada anterior, com navegador e VM disputando CPU, alguns casos passaram de ±100%, por isso vale sempre olhar essa coluna antes de comparar números.

### O que os números mostram

**Payload.** Protobuf é ~22% menor que JSON nos mesmos dados (295 B contra 377 B por produto; 28,6 KB contra 36,4 KB por lista de 100). O ganho vem de não repetir o nome dos campos e de codificar números em binário. O GraphQL é ligeiramente maior que o REST por causa do envelope `{"data": {...}}`.

**`single`: overhead por requisição.** Com o payload pequeno, o que pesa é o custo fixo de cada chamada. O WebSocket, com a conexão já aberta, só troca um frame em cada direção, sem parsear linha de requisição e headers HTTP, e chega a ~74k req/s com c=64 e p99 abaixo de 2 ms. O gRPC vem em seguida (51k), à frente do REST (31k) e do GraphQL (27k), porque multiplexa tudo numa conexão HTTP/2 com headers comprimidos (HPACK).

**`list`: custo de serialização.** Com 36 KB por resposta, serializar e desserializar domina. O gRPC faz ~1,6x mais requisições que o REST em todos os níveis de concorrência (5,9k contra 3,8k com c=1; 31,9k contra 20,1k com c=64), com latência p99 entre 40% e 60% da do REST. Gerar e ler Protobuf é bem mais barato que JSON. O WebSocket fica um pouco abaixo do REST: o payload é o mesmo JSON, e a vantagem de não ter overhead HTTP some quando a serialização domina. O **GraphQL fica em ~60% do throughput do REST**: a engine resolve cada campo de cada objeto individualmente (100 produtos × 8 campos = 800 resoluções por resposta). É o preço da flexibilidade de escolher campos.

**`stream`: mensagens pequenas em sequência.** O gRPC entrega ~900 mil itens por segundo com um único cliente, 4,3x o SSE e 5,8x o WebSocket, com a menor latência de cauda em todas as concorrências. O HTTP/2 do gRPC agrupa várias mensagens por escrita no socket. O SSE (`flush` a cada evento) e o WebSocket (um frame por mensagem) pagam uma chamada de sistema por item e mais o parse de JSON.

### A implementação importa tanto quanto o protocolo

A primeira versão do cliente gRPC usava o blocking stub padrão e ficou em último lugar no stream. Ajustes no cliente mudaram completamente o resultado (streams/s, mediana de 3 × 5 s, variação ≤ ±4%):

| Cliente gRPC (cenário `stream`, N = 1000) | c=1 | c=16 | c=64 |
|---|---:|---:|---:|
| Blocking stub (iterator) | 66 | 158 | 145 |
| Stub assíncrono (`StreamObserver`) | 95 | 231 | 228 |
| Stub assíncrono + `directExecutor()` no canal | **908** | **1.248** | **1.187** |

O blocking stub pede uma mensagem por vez e cada uma passa por duas trocas de thread (event loop do Netty → executor → thread que itera). Com `directExecutor()` os callbacks rodam direto no event loop, o que é seguro quando eles não bloqueiam, como aqui. O ganho foi de ~14x sem mudar nada no protocolo.

Os três modos continuam disponíveis para reproduzir a tabela (`direct` é o padrão):

```bash
JAVA_OPTS="-Dgrpc.client.mode=blocking" ./run-benchmark.sh --protocols=grpc --scenarios=stream
```

O mesmo ajuste no **servidor** é uma troca, não um ganho geral. Por isso ficou de fora (medições exploratórias):

| Servidor gRPC com `directExecutor()` | Padrão | Direct |
|---|---:|---:|
| `single`, c=64 (req/s) | 46.536 | **103.659** |
| `list`, c=64 (req/s) | **24.224** | 20.609 |
| `stream`, c=1 (streams/s) | **901** | 582 |

Chamadas curtas ficam 2x mais rápidas, mas o loop que envia 1000 mensagens passa a ocupar o event loop e atrasa todo o resto.

### Quando usar cada um

- **REST**: padrão para APIs públicas. Funciona em qualquer cliente, é fácil de cachear e depurar, e o desempenho é bom o bastante na maioria dos casos.
- **GraphQL**: quando clientes diferentes precisam de formatos diferentes dos mesmos dados (web, mobile, BFF). Troca CPU no servidor por menos requisições e menos over-fetching.
- **gRPC**: comunicação entre serviços, payloads grandes e streaming. Contrato tipado e payload menor, mas exige HTTP/2 e não roda direto no browser (precisa de gRPC-Web).
- **WebSocket**: comunicação bidirecional e de baixa latência sobre uma conexão persistente (chat, jogos, colaboração, dashboards ao vivo). O protocolo de mensagens, a correlação de requisições e a reconexão ficam por sua conta.
- **SSE**: notificações do servidor para o browser. É HTTP comum, reconecta sozinho e atravessa proxies, mas é unidirecional e só trafega texto.

### Tabelas completas

Cada linha é a mediana de 3 medições. Variação = (maior − menor throughput) / mediana.

### single — Buscar 1 produto por id (N = 1)

| Protocolo | Concorrência | req/s | variação | itens/s | p50 (ms) | p90 (ms) | p99 (ms) | max (ms) | payload | erros |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| rest | 1 | 10,823 | ±3% | 10,823 | 0.08 | 0.11 | 0.20 | 4.15 | 377 B | 0 |
| rest | 16 | 32,032 | ±2% | 32,032 | 0.48 | 0.68 | 1.12 | 8.35 | 377 B | 0 |
| rest | 64 | 30,621 | ±3% | 30,621 | 1.82 | 3.28 | 5.76 | 36.48 | 377 B | 0 |
| graphql | 1 | 7,147 | ±2% | 7,147 | 0.13 | 0.16 | 0.27 | 3.95 | 400 B | 0 |
| graphql | 16 | 27,195 | ±0% | 27,195 | 0.56 | 0.80 | 1.21 | 4.05 | 400 B | 0 |
| graphql | 64 | 26,878 | ±4% | 26,878 | 2.16 | 3.71 | 5.33 | 12.41 | 400 B | 0 |
| grpc | 1 | 13,000 | ±5% | 13,000 | 0.07 | 0.09 | 0.13 | 2.00 | 295 B | 0 |
| grpc | 16 | 43,049 | ±1% | 43,049 | 0.36 | 0.46 | 0.60 | 1.56 | 295 B | 0 |
| grpc | 64 | 50,894 | ±0% | 50,894 | 1.22 | 1.60 | 1.97 | 4.12 | 295 B | 0 |
| websocket | 1 | 18,209 | ±1% | 18,209 | 0.05 | 0.07 | 0.09 | 0.87 | 377 B | 0 |
| websocket | 16 | 65,778 | ±1% | 65,778 | 0.23 | 0.34 | 0.50 | 13.79 | 377 B | 0 |
| websocket | 64 | 73,769 | ±0% | 73,769 | 0.79 | 1.32 | 1.78 | 5.22 | 377 B | 0 |

### list — Listar N produtos numa única resposta (N = 100)

| Protocolo | Concorrência | req/s | variação | itens/s | p50 (ms) | p90 (ms) | p99 (ms) | max (ms) | payload | erros |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| rest | 1 | 3,759 | ±2% | 375,933 | 0.26 | 0.30 | 0.44 | 1.42 | 36.4 KB | 0 |
| rest | 16 | 19,585 | ±1% | 1,958,479 | 0.77 | 1.15 | 1.64 | 6.50 | 36.4 KB | 0 |
| rest | 64 | 20,145 | ±4% | 2,014,516 | 2.77 | 5.12 | 7.92 | 17.33 | 36.4 KB | 0 |
| graphql | 1 | 2,079 | ±3% | 207,911 | 0.46 | 0.51 | 0.91 | 2.33 | 36.6 KB | 0 |
| graphql | 16 | 12,321 | ±1% | 1,232,076 | 1.22 | 1.83 | 2.63 | 15.02 | 36.6 KB | 0 |
| graphql | 64 | 12,068 | ±1% | 1,206,846 | 4.91 | 8.06 | 12.64 | 45.73 | 36.6 KB | 0 |
| grpc | 1 | 5,912 | ±3% | 591,192 | 0.16 | 0.18 | 0.25 | 1.36 | 28.6 KB | 0 |
| grpc | 16 | 28,551 | ±1% | 2,855,100 | 0.55 | 0.70 | 0.92 | 1.91 | 28.6 KB | 0 |
| grpc | 64 | 31,898 | ±1% | 3,189,773 | 1.97 | 2.60 | 3.27 | 5.02 | 28.6 KB | 0 |
| websocket | 1 | 2,892 | ±2% | 289,239 | 0.34 | 0.37 | 0.45 | 11.94 | 36.4 KB | 0 |
| websocket | 16 | 16,445 | ±1% | 1,644,546 | 0.89 | 1.37 | 2.37 | 20.58 | 36.4 KB | 0 |
| websocket | 64 | 17,459 | ±0% | 1,745,906 | 3.43 | 5.62 | 8.45 | 39.87 | 36.4 KB | 0 |

### stream — Receber N produtos como stream de mensagens (N = 1000)

| Protocolo | Concorrência | req/s | variação | itens/s | p50 (ms) | p90 (ms) | p99 (ms) | max (ms) | payload | erros |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| rest | 1 | 209 | ±1% | 209,335 | 4.80 | 4.98 | 5.55 | 6.06 | 373.2 KB | 0 |
| rest | 16 | 651 | ±6% | 650,637 | 23.30 | 35.20 | 47.55 | 62.11 | 373.2 KB | 0 |
| rest | 64 | 637 | ±1% | 637,177 | 90.94 | 166.53 | 229.63 | 279.04 | 373.2 KB | 0 |
| grpc | 1 | 895 | ±2% | 894,825 | 1.11 | 1.20 | 1.65 | 2.50 | 285.9 KB | 0 |
| grpc | 16 | 1,206 | ±2% | 1,206,500 | 13.13 | 15.30 | 18.30 | 52.35 | 285.9 KB | 0 |
| grpc | 64 | 1,178 | ±1% | 1,177,640 | 53.70 | 59.97 | 71.62 | 83.33 | 285.9 KB | 0 |
| websocket | 1 | 155 | ±2% | 155,314 | 6.36 | 6.53 | 8.24 | 21.74 | 365.4 KB | 0 |
| websocket | 16 | 601 | ±1% | 601,308 | 25.44 | 37.54 | 50.43 | 65.92 | 365.4 KB | 0 |
| websocket | 64 | 580 | ±4% | 579,978 | 108.61 | 124.61 | 141.95 | 175.74 | 365.4 KB | 0 |

## gRPC em detalhe

Além dos três RPCs usados no benchmark, o serviço gRPC tem exemplos dos outros dois tipos de chamada, interceptors de autenticação e métricas, health check e reflection. Tudo coberto por testes que usam o transporte in-process do gRPC, sem abrir porta ([`ProductGrpcServiceTest`](server/src/test/java/br/com/diegobraun/protocolbench/server/grpc/ProductGrpcServiceTest.java)).

### Anatomia do `.proto`

O [`product.proto`](api/src/main/proto/product.proto) não é código de nenhuma linguagem. É uma IDL (*Interface Definition Language*): descreve mensagens e serviços de forma neutra, e o compilador `protoc` gera as classes para Java, Go, Python, TypeScript etc. A sintaxe lembra TypeScript ou C só porque também é declarativa e usa chaves.

```proto
syntax = "proto3";                                          // versão da linguagem
package protocolbench;                                      // namespace no fio (protocolbench.ProductService)
option java_package = "br.com.diegobraun.protocolbench.api.grpc";   // pacote das classes Java geradas

message Product {                                           // vira a classe Product (imutável, com builder)
  int64 id = 1;                                             // tipo, nome, NÚMERO DO CAMPO
  string name = 2;
  repeated string tags = 6;                                 // repeated = lista
  string created_at = 8;                                    // snake_case vira getCreatedAt() em Java
}
```

**O `= 1` não é valor padrão, é o número do campo.** É ele que vai para o fio, não o nome. Um produto com `id = 42` e `name = "Lamp"` é codificado assim:

```
08 2A              campo 1 (id), tipo varint, valor 42
12 04 4C 61 6D 70  campo 2 (name), tipo "tamanho + bytes", 4 bytes: "Lamp"
```

São 8 bytes, contra 23 do JSON `{"id":42,"name":"Lamp"}`. O primeiro byte de cada campo é `(número << 3) | tipo`: `08` = campo 1 varint, `12` = campo 2 com tamanho. Números de 1 a 15 cabem num único byte de cabeçalho, por isso os campos mais usados ficam neles.

Como o nome não trafega, as regras de evolução giram em torno dos números:

- **Adicionar campo é seguro.** Um cliente antigo ignora o número que não conhece, e um cliente novo que lê uma mensagem antiga recebe o valor padrão (`0`, `""`, `false`, lista vazia).
- **Renomear campo é seguro no fio**, mas quebra o código de quem usa as classes geradas.
- **Nunca mude o tipo nem reutilize o número de um campo.** Para remover um campo, marque o número como `reserved 9;` e ninguém poderá reutilizá-lo por engano.
- **Em proto3 não existe `null` para escalares.** `0` e "não enviado" são iguais. Se a diferença importar, declare o campo como `optional int32 stock = 7;` e use `hasStock()`.

| Tipo proto | Java | Observação |
|---|---|---|
| `int32`, `int64` | `int`, `long` | varint: números pequenos ocupam menos bytes |
| `double`, `float` | `double`, `float` | sempre 8 e 4 bytes |
| `bool` | `boolean` | |
| `string`, `bytes` | `String`, `ByteString` | |
| `repeated T` | `List<T>` | |
| outra `message` | classe gerada | mensagens podem ser aninhadas |

O `service` descreve as chamadas, e a palavra `stream` define o tipo de cada uma:

```proto
service ProductService {
  rpc GetProduct(GetProductRequest) returns (Product);                   // unary
  rpc StreamProducts(StreamProductsRequest) returns (stream Product);    // server streaming
  rpc UploadProducts(stream Product) returns (UploadSummary);            // client streaming
  rpc QuotePrices(stream PriceQuoteRequest) returns (stream PriceQuote); // bidirecional
}
```

No build (`mvn compile` no módulo `api`), o plugin gera em `api/target/generated-sources/protobuf`:

```java
Product product = Product.newBuilder().setId(42).setName("Lamp").build();
byte[] bytes = product.toByteArray();
Product copy = Product.parseFrom(bytes);

ProductServiceGrpc.ProductServiceBlockingStub stub = ProductServiceGrpc.newBlockingStub(channel);
ProductServiceGrpc.ProductServiceImplBase base;
```

### Os quatro tipos de chamada

| Tipo | RPC | Uso típico | Implementação |
|---|---|---|---|
| Unary | `GetProduct`, `ListProducts` | Requisição e resposta, como REST | `onNext` + `onCompleted` |
| Server streaming | `StreamProducts` | Exportar dados, feeds, resultados parciais | vários `onNext` + `onCompleted` |
| Client streaming | `UploadProducts` | Upload em lote, ingestão de métricas | o servidor devolve um `StreamObserver` e responde no `onCompleted` do cliente |
| Bidirecional | `QuotePrices` | Chat, cotações, sincronização | os dois lados enviam quando quiserem pela mesma chamada |

Em [`ProductGrpcService`](server/src/main/java/br/com/diegobraun/protocolbench/server/grpc/ProductGrpcService.java):

- **`UploadProducts`** recebe produtos um a um, rejeita os que vêm sem nome ou com preço zero e, quando o cliente fecha o stream, responde com o total aceito, rejeitado e o valor em estoque.
- **`QuotePrices`** responde cada pedido de cotação assim que ele chega, sem esperar os próximos. O teste confirma isso enviando um pedido, esperando a resposta e só então enviando o seguinte. Uma quantidade inválida encerra a chamada com `INVALID_ARGUMENT`.

### Interceptors

Interceptors fazem para o gRPC o que filtros fazem para HTTP. O servidor encadeia dois ao redor do `ProductService`:

```java
ServerInterceptors.intercept(new ProductGrpcService(), authInterceptor, metricsInterceptor)
```

O último da lista é executado primeiro. Assim as métricas veem inclusive as chamadas que a autenticação rejeita.

- **[`GrpcAuthInterceptor`](server/src/main/java/br/com/diegobraun/protocolbench/server/grpc/GrpcAuthInterceptor.java)** lê o header `authorization: Bearer <token>` dos `Metadata` da chamada. Se o token não existir, encerra com `UNAUTHENTICATED` antes de chegar ao serviço. Se existir, grava o nome do cliente no `Context` do gRPC, que acompanha a chamada até o serviço: `UploadProducts` usa isso para preencher `uploaded_by`. A autenticação fica desligada enquanto nenhum cliente estiver configurado:

  ```yaml
  grpc:
    auth:
      clients:
        inventory-service: token-do-inventory
        benchmark: token-do-benchmark
  ```

  No cliente, o token vai num interceptor de cliente (`MetadataUtils.newAttachHeadersInterceptor`). No gerador de carga basta passar `-Dgrpc.client.token=token-do-benchmark`.

- **[`GrpcMetricsInterceptor`](server/src/main/java/br/com/diegobraun/protocolbench/server/grpc/GrpcMetricsInterceptor.java)** envolve o `ServerCall` e, no `close`, registra método, status e duração. O resultado fica exposto em HTTP:

  ```bash
  curl localhost:8080/api/grpc/metrics
  # [{"method":"GetProduct","status":"OK","count":5225,"avgMs":0.04,"maxMs":6.55},
  #  {"method":"GetProduct","status":"UNAUTHENTICATED","count":83,"avgMs":0.02,"maxMs":0.2}]
  ```

  Num sistema real, esse papel seria de Micrometer ou OpenTelemetry, que já têm interceptors prontos. Aqui ele foi escrito à mão para mostrar o mecanismo.

Os resultados publicados foram medidos antes de os interceptors existirem. Uma rodada posterior só de gRPC, já com eles, ficou entre 0% e 10% abaixo dos números da tabela (ex.: `single` com c=64 foi de 50,9k para 45,7k req/s; `stream` com c=1 ficou igual). Como a máquina não estava no mesmo estado, essa diferença não isola o custo dos interceptors: seria preciso um A/B na mesma sessão.

### Health check e reflection

O servidor registra o [health check padrão do gRPC](https://grpc.io/docs/guides/health-checking/), usado por Kubernetes e load balancers, e o serviço de reflection. Com a reflection dá para chamar o servidor com o [grpcurl](https://github.com/fullstorydev/grpcurl) sem ter o `.proto`:

```bash
grpcurl -plaintext localhost:9090 list
grpcurl -plaintext -d '{"id": 42}' localhost:9090 protocolbench.ProductService/GetProduct
grpcurl -plaintext -d '{"product_id": 42, "quantity": 3} {"product_id": 7, "quantity": 1}' \
  localhost:9090 protocolbench.ProductService/QuotePrices
grpcurl -plaintext -d '{"service": "protocolbench.ProductService"}' localhost:9090 grpc.health.v1.Health/Check
```

## Como a medição funciona

- **Carga em loop fechado.** Cada worker é uma virtual thread que envia uma requisição, espera a resposta completa, registra a latência e envia a próxima. Com `c` workers há sempre no máximo `c` requisições em andamento. Isso mede a capacidade do sistema, não o comportamento sob uma taxa de chegada fixa.
- **A latência inclui a desserialização no cliente.** O JSON é convertido em `Product` e o Protobuf em objetos gerados, porque um cliente real sempre paga esse custo.
- **Toda resposta é validada.** Se vier com um número de itens diferente do esperado, conta como erro e não entra nas estatísticas.
- **HdrHistogram** registra as latências em microssegundos com 3 dígitos de precisão, sem perder a cauda como uma média faria.
- **O payload** é o corpo da resposta: bytes do JSON ou tamanho serializado do Protobuf. Headers HTTP, frames HTTP/2 e frames WebSocket não entram.
- **Aquecimento** de 5 s antes das medições, para a JIT compilar os caminhos quentes dos dois lados.
- **Repetição.** Cada combinação é medida 3 vezes e o relatório usa a mediana do throughput, junto com a dispersão entre as medições. Uma medição isolada num laptop pode variar bastante.

## Limitações

Benchmark de protocolo é fácil de interpretar errado. Os números aqui comparam **estas implementações Java, nesta máquina, com estes cenários**:

- Cliente e servidor rodam na mesma máquina por padrão e disputam CPU. Em loopback não há latência de rede, o que favorece protocolos verbosos: numa rede real, os bytes a mais do JSON pesariam mais.
- REST e GraphQL usam HTTP/1.1 sem TLS. Com HTTP/2 e TLS o cenário muda, principalmente em alta concorrência.
- O gRPC usa um único canal (uma conexão TCP) para todos os workers, que é o padrão recomendado. Em concorrência muito alta, vários canais podem render mais.
- WebSocket está no melhor caso possível: conexão já aberta e um worker por conexão, sem o custo do upgrade inicial nem correlação de mensagens concorrentes.
- Não há compressão (gzip) em nenhum protocolo.

## Estrutura

```
protocol-benchmark
├── api/      Product, catálogo determinístico e contrato .proto (gera o código gRPC)
├── server/   Spring Boot: REST, SSE, GraphQL, WebSocket (porta 8080) e gRPC com interceptors, health e reflection (porta 9090)
└── bench/    gerador de carga: um cliente por protocolo, runner com HdrHistogram e relatórios
```

Para adicionar um protocolo basta implementar `ProtocolClient` no módulo `bench`, o endpoint correspondente no `server` e registrar o cliente em `Clients`. O teste de integração passa a cobri-lo automaticamente.

## Licença

[MIT](LICENSE)
