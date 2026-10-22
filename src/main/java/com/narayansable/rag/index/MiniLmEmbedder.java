package com.narayansable.rag.index;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;

import java.util.List;

/**
 * all-MiniLM-L6-v2 sentence embeddings (384 dimensions), quantized and run in-process with ONNX
 * Runtime: semantic search with no API key, no network call and no per-query cost, so customer
 * questions never leave the service for an embedding provider either. The model loads on first use.
 */
public class MiniLmEmbedder implements Embedder {

    private volatile EmbeddingModel model;

    private EmbeddingModel model() {
        EmbeddingModel m = model;
        if (m == null) {
            synchronized (this) {
                if (model == null) {
                    model = new AllMiniLmL6V2QuantizedEmbeddingModel();
                }
                m = model;
            }
        }
        return m;
    }

    @Override
    public int dimension() {
        return 384;
    }

    @Override
    public float[] embed(String text) {
        return normalize(model().embed(text).content().vector());
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        return model().embedAll(texts.stream().map(TextSegment::from).toList()).content().stream()
                .map(e -> normalize(e.vector())).toList();
    }

    static float[] normalize(float[] v) {
        double s = 0;
        for (float x : v) {
            s += x * x;
        }
        double n = Math.sqrt(s);
        if (n == 0) {
            return v;
        }
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] / n);
        }
        return out;
    }
}
