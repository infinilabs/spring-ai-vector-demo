package com.infinilabs.esdemo;

import com.infinilabs.clients.easysearch.EasysearchClient;
import com.infinilabs.clients.json.jackson.JacksonJsonpMapper;
import com.infinilabs.clients.transport.EasysearchTransport;
import com.infinilabs.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.nio.conn.ssl.SSLIOSessionStrategy;
import org.apache.http.ssl.SSLContextBuilder;
import org.easysearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;
import java.security.cert.X509Certificate;

/**
 * 自建 EasysearchClient（官方 easysearch-client，底层 Apache HC4 + org.easysearch.client.RestClient）。
 *
 * <p>不再使用 Spring Boot 的 Rest5Client auto-config。HTTPS 自签名：trust-all + 跳过主机名校验
 * （仅本地联调）。basic auth 从 {@code easysearch.*} 读取。
 */
@Configuration
public class EasysearchClientConfig {

	@Value("${easysearch.host:localhost}")
	private String host;

	@Value("${easysearch.port:9200}")
	private int port;

	@Value("${easysearch.username}")
	private String username;

	@Value("${easysearch.password}")
	private String password;

	@Bean(destroyMethod = "close")
	public RestClient restClient() throws Exception {
		SSLContext sslContext = SSLContextBuilder.create()
			.loadTrustMaterial(null, (X509Certificate[] chain, String authType) -> true)
			.build();
		SSLIOSessionStrategy sessionStrategy = new SSLIOSessionStrategy(sslContext, NoopHostnameVerifier.INSTANCE);

		BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
		credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, password));

		return RestClient.builder(new HttpHost(host, port, "https"))
			.setHttpClientConfigCallback(httpClientBuilder -> httpClientBuilder
				.setDefaultCredentialsProvider(credentialsProvider)
				.setSSLStrategy(sessionStrategy)
				.disableAuthCaching())
			.setRequestConfigCallback(requestConfigBuilder -> requestConfigBuilder
				.setConnectTimeout(5000)
				.setSocketTimeout(30000))
			.build();
	}

	@Bean
	public EasysearchClient easysearchClient(RestClient restClient) {
		EasysearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
		return new EasysearchClient(transport);
	}

}
