package com.infinilabs.esdemo;

import java.util.List;
import java.util.Map;

import com.infinilabs.clients.easysearch.EasysearchClient;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Spring AI + Easysearch 向量存储演示。
 *
 * <p>启动时通过 Spring AI {@link VectorStore} 写入新闻标题，并直接调用
 * {@link VectorStore#similaritySearch(SearchRequest)} 做语义相近查询。REST 接口只作为额外交互入口：
 * POST /api/docs、GET /api/search。
 *
 * <p>依赖独立发布的 {@code spring-ai-easysearch-vectorstore}（EasysearchVectorStore）+ 官方 easysearch-client。
 */
@SpringBootApplication
public class EsDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(EsDemoApplication.class, args);
    }

	/** 启动时写入样例文档，便于演示时直接检索（如不希望重复写入，可删除此 bean）。 */
	@Bean
	CommandLineRunner runNewsTitleVectorDemo(VectorStore vectorStore, EasysearchClient easysearchClient,
			@Value("${spring.ai.vectorstore.easysearch.index-name}") String indexName) {
		return args -> {
			vectorStore.delete(List.of("sample-spring-ai", "sample-knn", "sample-compare", "sample-noise"));
			vectorStore.delete(newsDocumentIds());
			vectorStore.add(newsDocuments());
			easysearchClient.indices().refresh(r -> r.index(indexName));

				System.out.println();
				System.out.println(">> 新闻标题样例已通过 Spring AI VectorStore 写入 Easysearch 索引：" + indexName);
				System.out.println(">> 以下查询由 spring-ai-vector-demo 内部直接调用 VectorStore.similaritySearch(...) 完成，"
						+ "底层实现来自 spring-ai-easysearch-vectorstore。");
			printSearchResult("人工智能产业发展", vectorStore.similaritySearch(SearchRequest.builder()
				.query("人工智能产业发展")
				.topK(3)
				.similarityThreshold(0.0)
				.build()));
			printSearchResult("体育比赛晋级", vectorStore.similaritySearch(SearchRequest.builder()
				.query("体育比赛晋级")
				.topK(3)
				.similarityThreshold(0.0)
				.build()));
			printSearchResult("企业智能化 + topic=technology", vectorStore.similaritySearch(SearchRequest.builder()
				.query("企业智能化")
				.topK(3)
				.similarityThreshold(0.0)
				.filterExpression(new Filter.Expression(Filter.ExpressionType.EQ, new Filter.Key("topic"),
						new Filter.Value("technology")))
				.build()));
			System.out.println(">> REST 交互入口仍可使用：curl -G 'http://localhost:8080/api/search' "
					+ "--data-urlencode 'q=人工智能产业发展' -d 'topK=3'");
			System.out.println();
		};
	}

	private static List<Document> newsDocuments() {
		return List.of(
					new Document("news-ai", "阿里云发布新一代通义千问大模型，提升企业智能化应用能力",
							Map.of("topic", "technology", "type", "news-title")),
					new Document("news-finance", "央行宣布下调存款准备金率，释放长期流动性支持实体经济",
							Map.of("topic", "finance", "type", "news-title")),
					new Document("news-sports", "中国女排逆转战胜强敌，晋级世界联赛四强",
							Map.of("topic", "sports", "type", "news-title")),
					new Document("news-travel", "暑期旅游市场持续升温，多地景区迎来客流高峰",
							Map.of("topic", "travel", "type", "news-title")),
					new Document("news-auto", "新能源汽车销量再创新高，智能驾驶成为竞争焦点",
							Map.of("topic", "auto", "type", "news-title")),
					new Document("news-health", "新型流感疫苗进入临床试验，专家提醒关注秋冬防护",
							Map.of("topic", "health", "type", "news-title")));
	}

	private static List<String> newsDocumentIds() {
		return newsDocuments().stream().map(Document::getId).toList();
	}

	private static void printSearchResult(String query, List<Document> hits) {
		System.out.println(">> Query: " + query);
		for (int i = 0; i < hits.size(); i++) {
			Document hit = hits.get(i);
			System.out.printf("   %d. score=%.4f topic=%s id=%s text=%s%n", i + 1, hit.getScore(),
					hit.getMetadata().get("topic"), hit.getId(), hit.getText());
		}
	}

}
