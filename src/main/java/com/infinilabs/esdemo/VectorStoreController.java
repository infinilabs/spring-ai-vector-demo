package com.infinilabs.esdemo;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infinilabs.clients.easysearch.EasysearchClient;
import com.infinilabs.clients.easysearch.core.SearchResponse;
import com.infinilabs.clients.easysearch.core.search.Hit;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 向量存储演示 REST 接口。
 *
 * <ul>
 * <li>POST /api/docs —— 写入文档（文本数组，自动 embedding 入库）</li>
 * <li>GET /api/search?q=...&amp;topK=3&amp;threshold=0.0&amp;metaKey=topic&amp;metaValue=technology —— 语义检索
 * （可带相似度阈值、metadata 过滤）</li>
 * <li>GET /api/fulltext/search?q=...&amp;topK=3&amp;metaKey=topic&amp;metaValue=technology —— Easysearch 原生全文检索
 * （可带 metadata 过滤）</li>
 * <li>DELETE /api/docs/{id} —— 按 id 删除</li>
 * </ul>
 *
 * <p>默认 profile 使用 StubEmbeddingModel（确定性假向量）；dashscope profile 使用阿里云 DashScope
 * OpenAI-compatible embedding，可用于真实语义相近查询演示。
 */
@RestController
@RequestMapping("/api")
public class VectorStoreController {

	private final VectorStore vectorStore;

	private final EasysearchClient easysearchClient;

	private final ObjectMapper objectMapper;

	private final String indexName;

	public VectorStoreController(VectorStore vectorStore, EasysearchClient easysearchClient, ObjectMapper objectMapper,
				@Value("${spring.ai.vectorstore.easysearch.index-name}") String indexName) {
		this.vectorStore = vectorStore;
		this.easysearchClient = easysearchClient;
		this.objectMapper = objectMapper;
		this.indexName = indexName;
	}

	@PostMapping("/docs")
	public AddResponse add(@RequestBody AddRequest request) {
		if (request.texts() == null || request.texts().isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "`texts` must not be empty");
		}
		Map<String, Object> metadata = request.metadata() == null ? Map.of() : request.metadata();
		List<Document> docs = request.texts().stream().map(t -> new Document(t, metadata)).toList();
		this.vectorStore.add(docs);
		refreshIndex();
		return new AddResponse(docs.size(), docs.stream().map(Document::getId).toList());
	}

	@DeleteMapping("/docs/{id}")
	public Map<String, String> delete(@PathVariable String id) {
		this.vectorStore.delete(List.of(id));
		refreshIndex();
		return Map.of("deleted", id);
	}

	@GetMapping("/search")
	public List<SearchHit> search(@RequestParam String q, @RequestParam(defaultValue = "3") int topK,
				@RequestParam(defaultValue = "0.0") double threshold,
			@RequestParam(required = false) String metaKey, @RequestParam(required = false) String metaValue) {
		SearchRequest.Builder builder = SearchRequest.builder()
			.query(q)
			.topK(topK)
				.similarityThreshold(threshold);
		if (metaKey != null && !metaKey.isBlank()) {
			if (metaValue == null) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "`metaValue` is required when `metaKey` is set");
			}
			// 演示 filter：metadata.<metaKey> == <metaValue>（store 会转成 bool filter query_string）
			builder.filterExpression(new Filter.Expression(Filter.ExpressionType.EQ, new Filter.Key(metaKey),
					new Filter.Value(metaValue)));
			}
			return this.vectorStore.similaritySearch(builder.build())
				.stream()
				.map(d -> new SearchHit(d.getId(), d.getText(), d.getScore(), d.getMetadata()))
					.toList();
	}

	@GetMapping("/fulltext/search")
	public List<SearchHit> fullTextSearch(@RequestParam String q, @RequestParam(defaultValue = "3") int topK,
			@RequestParam(required = false) String metaKey, @RequestParam(required = false) String metaValue) {
		if (metaKey != null && !metaKey.isBlank() && metaValue == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "`metaValue` is required when `metaKey` is set");
		}
		try {
			SearchResponse<ObjectNode> response = this.easysearchClient.search(s -> s.index(this.indexName)
				.size(topK)
				.query(qb -> qb.bool(b -> b
					.must(m -> m.match(match -> match.field("content").query(q)))
					.filter(metaKey == null || metaKey.isBlank()
							? f -> f.matchAll(ma -> ma)
							: f -> f.term(t -> t.field("metadata." + metaKey).value(metaValue))))),
					ObjectNode.class);
			return response.hits().hits().stream().map(this::toSearchHit).toList();
		}
		catch (IOException e) {
			throw new IllegalStateException("Failed to execute full-text search against Easysearch index " + this.indexName, e);
		}
	}

	private void refreshIndex() {
		try {
			this.easysearchClient.indices().refresh(r -> r.index(this.indexName));
		}
		catch (IOException e) {
			throw new IllegalStateException("Failed to refresh Easysearch index " + this.indexName, e);
		}
	}

	private SearchHit toSearchHit(Hit<ObjectNode> hit) {
		ObjectNode source = hit.source();
		if (source == null) {
			throw new IllegalStateException("Easysearch hit source is unexpectedly null");
		}
		JsonNode metadataNode = source.get("metadata");
		Map<String, Object> metadata = new HashMap<>();
		if (metadataNode != null && !metadataNode.isNull()) {
			metadata.putAll(this.objectMapper.convertValue(metadataNode, new TypeReference<Map<String, Object>>() {
			}));
		}
		String id = source.has("id") ? source.get("id").asText() : hit.id();
		String text = source.has("content") ? source.get("content").asText() : null;
		Double score = (hit.score() == null) ? null : hit.score().doubleValue();
		return new SearchHit(id, text, score, metadata);
	}

	/** 请求体：{"texts": ["一段文本", "另一段"], "metadata": {"topic": "custom"}} */
	public record AddRequest(List<String> texts, Map<String, Object> metadata) {
	}

	/** 写入响应：写入数量 + 生成的文档 id。 */
	public record AddResponse(int added, List<String> ids) {
	}

	/** 检索命中：id + 文本 + 相似度分数（0~1）+ 元数据 */
	public record SearchHit(String id, String text, Double score, Map<String, Object> metadata) {
	}

}
