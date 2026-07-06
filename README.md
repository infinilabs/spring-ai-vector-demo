# Spring AI + Easysearch 向量存储演示

基于 Spring AI 2.0 的 `VectorStore` 抽象 + Maven Central 上的
`com.infinilabs:spring-ai-easysearch-vectorstore`，
演示如何在 Spring Boot 应用里通过 Spring AI `VectorStore` 把文本写入 Easysearch 的
`knn_dense_float_vector` 字段，并通过 `knn_nearest_neighbors` 查询语义相近结果。
项目启动后会自动执行一组新闻标题向量写入和语义查询；REST 接口只作为额外交互入口。

完整链路：

1. `spring-ai-easysearch-vectorstore` 提供 Spring AI `VectorStore` 实现，把 Spring AI 标准调用适配到 Easysearch 原生 kNN。
2. `spring-ai-vector-demo` 提供可运行应用，调用阿里云 DashScope `text-embedding-v4` 生成新闻标题向量。
3. `VectorStore.add(...)` 把新闻标题、metadata 和向量写入 Easysearch。
4. `spring-ai-vector-demo` 启动后直接调用 `VectorStore.similaritySearch(...)`，把查询文本向量化后，
   在 Easysearch 中做语义相近检索。

> 依赖独立发布的 `spring-ai-easysearch-vectorstore`（`EasysearchVectorStore`，
> fork 自 `spring-ai-elasticsearch-store`，改用 Easysearch 的 `knn_dense_float_vector` 字段类型 +
> `knn_nearest_neighbors` 查询）。
>
> Maven Central:
> `com.infinilabs:spring-ai-easysearch-vectorstore:2.3.0`
> 本 demo 演示最常见的一种用法：auto-config + Spring AI `VectorStore` + Easysearch，
> 默认 Stub embedding 可离线验证链路，`dashscope` profile 可调用阿里云文本嵌入做真实语义查询。

## Spring AI 标准用法

本项目的核心目的不是让业务代码直接调用 Easysearch kNN API，而是演示：
Easysearch 已经通过 `spring-ai-easysearch-vectorstore` 封装成 Spring AI 标准的 `VectorStore`。
因此业务代码只需要注入 `VectorStore`，按 Spring AI 的标准方式写入和检索文档。

Maven 依赖如下：

```xml
<dependency>
    <groupId>com.infinilabs</groupId>
    <artifactId>spring-ai-easysearch-vectorstore</artifactId>
    <version>2.3.0</version>
</dependency>
```

最小用法如下：

```java
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeService {

    private final VectorStore vectorStore;

    public KnowledgeService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public void addDocs() {
        this.vectorStore.add(List.of(
                new Document("Easysearch 支持 kNN 向量检索", Map.of("topic", "knn")),
                new Document("Spring AI 提供统一的 VectorStore 抽象", Map.of("topic", "spring-ai"))));
    }

    public List<Document> search(String query) {
        return this.vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(3)
                .similarityThreshold(0.0)
                .build());
    }
}
```

本 demo 启动时会直接执行上面的 `vectorStore.add(...)`、`vectorStore.similaritySearch(...)`
链路；REST 接口只是为了启动后继续交互。实际业务项目可以直接在 Service、Controller、RAG 流程中注入并使用 `VectorStore`。

## 演示接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/docs` | 写入文档（文本数组，自动 embedding 入库）。body：`{"texts":["..."],"metadata":{"topic":"custom"}}` |
| GET | `/api/search?q=<关键词>&topK=3&threshold=0.0&metaKey=topic&metaValue=technology` | 语义相似度检索，可带相似度阈值、metadata 过滤；返回 id + 文本 + score + 元数据 |
| GET | `/api/fulltext/search?q=<关键词>&topK=3&metaKey=topic&metaValue=technology` | Easysearch 原生全文检索，直接对 `content` 做 `match` 查询；可带 metadata 过滤 |
| DELETE | `/api/docs/{id}` | 按 id 删除文档 |

> 默认 embedding 用 `StubEmbeddingModel`（确定性假向量），**不依赖外部服务/Key，开箱即跑**。
> 检索结果是 hash 相似（非真实语义）。如需真实语义，可启用 `dashscope` profile，
> 通过 Spring AI 的 OpenAI-compatible `EmbeddingModel` 调用阿里云 DashScope。
> 为了演示稳定，小数据集默认使用 `model: exact`；大数据压测或近似召回演示时可改成 `lsh`。

## 全文搜索怎么调

需要区分两件事：

1. `VectorStore.similaritySearch(...)` 是 Spring AI 抽象，负责语义检索。
2. 全文搜索不是 `VectorStore` 的标准能力，应该直接调用 Easysearch 客户端。

这个 demo 已经提供了一个现成入口：`GET /api/fulltext/search`。它内部没有走
`VectorStore`，而是直接用 `EasysearchClient` 对 `content` 字段执行 `match` 查询。

Java 示例：

```java
import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infinilabs.clients.easysearch.EasysearchClient;
import com.infinilabs.clients.easysearch.core.SearchResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class NewsFullTextSearchService {

    private final EasysearchClient easysearchClient;

    @Value("${spring.ai.vectorstore.easysearch.index-name}")
    private String indexName;

    public NewsFullTextSearchService(EasysearchClient easysearchClient) {
        this.easysearchClient = easysearchClient;
    }

    public List<String> search(String keyword) throws IOException {
        SearchResponse<ObjectNode> response = this.easysearchClient.search(s -> s
                .index(this.indexName)
                .size(3)
                .query(q -> q.match(m -> m.field("content").query(keyword))), ObjectNode.class);

        return response.hits().hits().stream()
                .map(hit -> hit.source().get("content").asText())
                .toList();
    }
}
```

如果要加业务 metadata 过滤，可以把查询改成 `bool + must(match) + filter(term)`，例如只查
`metadata.topic=technology`：

```java
.query(qb -> qb.bool(b -> b
        .must(m -> m.match(match -> match.field("content").query(keyword)))
        .filter(f -> f.term(t -> t.field("metadata.topic").value("technology")))))
```

对应的 HTTP 调用就是：

```bash
curl -G 'http://localhost:8080/api/fulltext/search' \
  --data-urlencode 'q=企业智能化' \
  -d 'topK=3' \
  -d 'metaKey=topic' \
  -d 'metaValue=technology'
```

## 前置条件

1. **Easysearch**（开启 kNN 插件 + ES API 兼容）
   - 安装 `knn` 插件
   - `easysearch.yml`：
     ```yaml
     elasticsearch.api_compatibility: true
     elasticsearch.api_compatibility_version: "8.19.17"
     ```
   - 保留 HTTPS + basic auth（默认 `admin` / `<密码>`）
2. **Maven Central 可访问**
   - 首次构建时，Maven 会自动拉取 `com.infinilabs:spring-ai-easysearch-vectorstore:2.3.0`
3. JDK 17+（Spring Boot 4.1 / spring-ai 2.0 baseline）

## 配置

`src/main/resources/application.yml`：
```yaml
easysearch:
  host: localhost
  port: 9200
  username: admin
  password: ${EASYSEARCH_PASSWORD:}   # 通过环境变量注入，不写死
spring:
  ai:
    model:
      embedding: none                  # 默认使用 StubEmbeddingModel；dashscope profile 会改成 openai
    vectorstore:
      easysearch:
        initialize-schema: true
        index-name: spring-ai-easysearch-demo
        dimensions: 384
        similarity: cosine
        model: exact        # Demo 小数据集默认 exact；大数据可改 lsh
        l: 99
        k: 1
        candidates: 50
```

启动时会写入 6 条固定 id 的新闻标题样例文档；重复启动会覆盖这些样例文档，不会无限新增重复数据。
写入、删除后 demo 会主动 refresh 索引，便于启动自动演示和可选 REST 交互马上查询到结果。

## 运行

默认模式使用 `StubEmbeddingModel`，不需要外部模型 API Key：

```bash
EASYSEARCH_PASSWORD='<你的密码>' mvn spring-boot:run
```

启动后自动写入 6 条新闻标题样例文档，监听 `8080`。

如果要调用阿里云 DashScope 的 OpenAI 兼容文本向量接口，启用 `dashscope` profile：

```bash
DASHSCOPE_API_KEY='<你的 DashScope API Key>' \
EASYSEARCH_PASSWORD='<你的 Easysearch 密码>' \
mvn spring-boot:run -Dspring-boot.run.profiles=dashscope
```

注意：profile 名称必须精确写成 `dashscope`，不要误写成 `dashscop` 或其他变体，否则
`application-dashscope.yml` 不会生效，启动时会出现 OpenAI credential 相关报错。

`dashscope` profile 会关闭 `StubEmbeddingModel`，改用 Spring AI 自动装配的 OpenAI-compatible
`EmbeddingModel`，模型为 `text-embedding-v4`，向量维度为 `256`。
该 profile 使用独立索引 `spring-ai-easysearch-demo-dashscope`，避免和默认 384 维 stub 索引冲突。

也可以直接运行完整演示脚本。脚本会直接从 Maven Central 拉取依赖并启动本 demo；
新闻标题写入和语义查询都由 `spring-ai-vector-demo` 内部 Java 代码直接调用 Spring AI `VectorStore` 完成：

```bash
DASHSCOPE_API_KEY='<你的 DashScope API Key>' \
EASYSEARCH_PASSWORD='<你的 Easysearch 密码>' \
./scripts/run-dashscope-news-demo.sh
```

## 启动时自动演示

启用 `dashscope` profile 后，应用启动时会自动执行：

1. 使用阿里云 DashScope `text-embedding-v4` 把 6 条新闻标题向量化。
2. 通过 `spring-ai-easysearch-vectorstore` 提供的 `EasysearchVectorStore` 写入 Easysearch。
3. 继续在应用内部调用 `VectorStore.similaritySearch(...)` 做三组查询：
   - `人工智能产业发展`
   - `体育比赛晋级`
   - `企业智能化 + topic=technology`

控制台会输出类似：

```text
>> 新闻标题样例已通过 Spring AI VectorStore 写入 Easysearch 索引：spring-ai-easysearch-demo-dashscope
>> 以下查询由 spring-ai-vector-demo 内部直接调用 VectorStore.similaritySearch(...) 完成，底层实现来自 spring-ai-easysearch-vectorstore。
>> Query: 人工智能产业发展
   1. score=... topic=technology id=news-ai text=阿里云发布新一代通义千问大模型，提升企业智能化应用能力
```

## 交互接口（可选 curl）

```bash
# 语义相近查询：查询“人工智能产业发展”，通常会命中 AI/智能驾驶相关新闻标题
curl -G 'http://localhost:8080/api/search' \
  --data-urlencode 'q=人工智能产业发展' \
  -d 'topK=3'

# 语义相近查询：查询“体育比赛晋级”，通常会命中体育相关新闻标题
curl -G 'http://localhost:8080/api/search' \
  --data-urlencode 'q=体育比赛晋级' \
  -d 'topK=3'

# metadata 过滤：只查 topic=technology 的新闻标题
curl -G 'http://localhost:8080/api/search' \
  --data-urlencode 'q=企业智能化' \
  -d 'topK=3' \
  -d 'metaKey=topic' \
  -d 'metaValue=technology'

# 原生全文搜索：直接对 content 做 match 查询
curl -G 'http://localhost:8080/api/fulltext/search' \
  --data-urlencode 'q=人工智能产业发展' \
  -d 'topK=3'

# 原生全文搜索 + metadata 过滤：只查 topic=technology 的全文结果
curl -G 'http://localhost:8080/api/fulltext/search' \
  --data-urlencode 'q=企业智能化' \
  -d 'topK=3' \
  -d 'metaKey=topic' \
  -d 'metaValue=technology'

# 写入自己的新闻标题，并带统一 metadata
curl -X POST http://localhost:8080/api/docs \
  -H 'Content-Type: application/json' \
  -d '{"texts":["国产大模型加速落地金融风控场景","多家车企发布城市智能驾驶新功能"],"metadata":{"topic":"custom-news","type":"news-title"}}'

# 查询刚写入的自定义新闻标题
curl -G 'http://localhost:8080/api/search' \
  --data-urlencode 'q=智能驾驶汽车' \
  -d 'topK=5' \
  -d 'metaKey=topic' \
  -d 'metaValue=custom-news'
```

如果返回结果里能看到 `id`、`text`、`score`、`metadata`，说明 REST 层到 Spring AI `VectorStore`
再到 Easysearch 的交互链路也已经跑通。

> 使用默认 `StubEmbeddingModel` 时，结果只能证明链路可用；启用 `dashscope` profile 后，
> 文本会通过阿里云 DashScope `text-embedding-v4` 向量化，查询结果才具备真实语义相近性。

补充说明：

- `metadata.topic`、`metadata.type` 是写入文档时的业务 metadata
- `metadata.distance` 是 `spring-ai-easysearch-vectorstore` 在查询结果返回时补充的派生字段，
  当前 demo 的 `cosine` 模式下近似等于 `1 - score`
- `score` 越大越相似，`distance` 越小越接近

## 换成真实 Embedding

本 demo 默认使用 `StubEmbeddingModel`，它基于文本 hash 生成 384 维确定性向量，适合验证接入链路，
但不具备真实语义能力。

如果要演示真实语义检索，可以启用已内置的 `dashscope` profile，或替换成 Ollama、OpenAI
等其他 embedding 模型。替换时注意两点：

1. 容器里只能有一个主要的 `EmbeddingModel` Bean，避免自动装配冲突。
2. `spring.ai.vectorstore.easysearch.dimensions` 必须和 embedding 模型输出维度一致。

### DashScope / OpenAI-compatible 示例

`src/main/resources/application-dashscope.yml` 使用 Spring AI 的 OpenAI starter 调用
DashScope compatible-mode：

```yaml
spring:
  ai:
    model:
      embedding: openai
    openai:
      api-key: ${DASHSCOPE_API_KEY}
      embedding:
        base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
        options:
          model: text-embedding-v4
          dimensions: 256
    vectorstore:
      easysearch:
        index-name: spring-ai-easysearch-demo-dashscope
        dimensions: 256
```

注意：API Key 通过环境变量 `DASHSCOPE_API_KEY` 注入，不要写死到配置文件或提交到代码仓库。

例如使用 768 维模型时：

```yaml
spring:
  ai:
    vectorstore:
      easysearch:
        dimensions: 768
```

## 常见问题

**启动时报 `Index not found`**

确认配置里 `spring.ai.vectorstore.easysearch.initialize-schema: true`。该配置会在索引不存在时自动创建
`knn_dense_float_vector` mapping。

**建索引时报 `No handler for type [knn_dense_float_vector]`**

Easysearch 没有安装或启用 kNN 插件。需要先安装 kNN 插件，再重启 Easysearch。

**请求 Easysearch 失败或客户端解析异常**

确认 Easysearch 已开启 Elasticsearch API 兼容配置：

```yaml
elasticsearch.api_compatibility: true
elasticsearch.api_compatibility_version: "8.19.17"
```

**写入时报向量维度不匹配**

确认 `spring.ai.vectorstore.easysearch.dimensions` 和当前 `EmbeddingModel` 实际输出维度一致。
本 demo 的 `StubEmbeddingModel` 是 384 维。

**启动时认证失败**

确认 `EASYSEARCH_PASSWORD` 环境变量和 Easysearch `admin` 用户密码一致。

**启动时报 `At least one credential source must be specified`**

先确认两件事：

1. 启动命令里已经传入 `DASHSCOPE_API_KEY`
2. `-Dspring-boot.run.profiles=dashscope` 里的 profile 名称没有拼错

如果 profile 没有精确命中 `dashscope`，`application-dashscope.yml` 不会加载，Spring AI OpenAI starter
就拿不到 `spring.ai.openai.api-key`。

## 安全说明

`EasysearchClientConfig` 为了本地演示方便，使用了 trust-all SSL 和跳过主机名校验。
生产环境不要直接照搬，应改为使用正式 CA 证书、明确的主机名校验和安全的凭据管理方式。

## 工程结构

```
src/main/java/com/infinilabs/esdemo/
├── EsDemoApplication.java        # 启动 + 写入样例
├── VectorStoreController.java    # REST 接口（/api/docs、/api/search、/api/fulltext/search）
├── EasysearchClientConfig.java   # 自建 EasysearchClient（HC4 trust-all + auth）
└── StubEmbeddingModel.java       # 确定性 stub embedding（384 维）
```

## 参考

- Easysearch 向量搜索文档：<https://docs.infinilabs.com/easysearch/main/docs/features/vector-search/>
  （`knn_dense_float_vector` 字段 + `knn_nearest_neighbors` 查询）
- Easysearch Java Client 文档：<https://docs.infinilabs.com/easysearch/main/docs/integrations/clients/java/>
- Spring AI `VectorStore` 抽象
