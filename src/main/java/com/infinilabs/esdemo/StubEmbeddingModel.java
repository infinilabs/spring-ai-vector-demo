package com.infinilabs.esdemo;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Stub EmbeddingModel：基于文本 hash 生成确定性向量（相同文本→相同向量，不同文本→不同向量），
 * 仅用于验证向量存储的连通性、knn_dense_float_vector mapping、写入与 knn 查询通路。
 *
 * <p><b>无语义</b>：不要用它评估检索质量。验证通过后可换成 Ollama / OpenAI 等真实 embedding。
 */
@Component
@Profile("!dashscope & !ollama")
public class StubEmbeddingModel implements EmbeddingModel {

    public static final int DIM = 384;

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> inputs = request.getInstructions();
        List<Embedding> results = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            results.add(new Embedding(vector(inputs.get(i)), i));
        }
        return new EmbeddingResponse(results);
    }

    @Override
    public float[] embed(Document document) {
        return vector(document.getText());
    }

    @Override
    public int dimensions() {
        return DIM;
    }

    private static float[] vector(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        float[] v = new float[DIM];
        long h = 2166136261L;
        for (int i = 0; i < DIM; i++) {
            h ^= (i < bytes.length ? bytes[i] : (byte) i);
            h *= 16777619L;
            v[i] = (float) ((h & 0xFFFFFFFFL) / (double) 0xFFFFFFFFL);
        }
        // L2 归一化，便于 cosine 相似度有区分度
        double norm = 0;
        for (float f : v) {
            norm += f * f;
        }
        norm = Math.sqrt(norm) + 1e-9;
        for (int i = 0; i < DIM; i++) {
            v[i] = (float) (v[i] / norm);
        }
        return v;
    }
}
