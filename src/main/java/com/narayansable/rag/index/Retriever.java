package com.narayansable.rag.index;

import com.narayansable.rag.config.RagProperties;
import com.narayansable.rag.ingest.Chunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hybrid retrieval: lexical evidence ({@link LexicalIndex}) blended with semantic similarity
 * ({@link VectorStore}) into one 0..1 score.
 *
 * <p>Lexical search is precise on what banking questions usually hinge on - rare exact terms such
 * as RTGS, IFSC or maker-checker. Semantic search finds the passage when the wording differs
 * ("when does money move between our accounts automatically" -> sweeps). The cosine is rescaled
 * so unrelated text scores 0 ({@code semanticFloor}); the blend keeps a fixed scale, so one
 * threshold still decides "not in the documents".
 */
public class Retriever {

    private final LexicalIndex lexical;
    private final VectorStore vectors;
    private final Embedder embedder;
    private final double semanticWeight;
    private final double semanticFloor;

    public Retriever(LexicalIndex lexical, VectorStore vectors, Embedder embedder, RagProperties.Retrieval cfg) {
        this.lexical = lexical;
        this.semanticWeight = cfg.semanticEnabled() && vectors != null ? cfg.semanticWeight() : 0;
        this.vectors = semanticWeight > 0 ? vectors : null;
        this.embedder = semanticWeight > 0 ? embedder : null;
        this.semanticFloor = cfg.semanticFloor();
    }

    /** Lexical only - for tests and deployments that turn semantic search off. */
    public Retriever(LexicalIndex lexical) {
        this(lexical, null, null, new RagProperties.Retrieval(0, 0, "memory"));
    }

    public void replaceDocument(String documentId, List<Chunk> chunks) {
        if (vectors != null) {
            vectors.replaceDocument(documentId, chunks);
        }
        lexical.replaceDocument(documentId, chunks);
    }

    public void removeDocument(String documentId) {
        lexical.removeDocument(documentId);
        if (vectors != null) {
            vectors.removeDocument(documentId);
        }
    }

    public List<ScoredChunk> search(String query, int k) {
        Map<String, Double> lex = lexical.scores(query);
        Map<String, Double> sem = new HashMap<>();
        if (vectors != null && !query.isBlank()) {
            for (VectorStore.Hit h : vectors.search(embedder.embed(query), Math.max(4 * k, 20))) {
                double s = (h.cosine() - semanticFloor) / (1 - semanticFloor);
                if (s > 0) {
                    sem.put(h.chunkId(), Math.min(1, s));
                }
            }
        }
        Set<String> candidates = new LinkedHashSet<>(lex.keySet());
        candidates.addAll(sem.keySet());
        List<ScoredChunk> hits = new ArrayList<>();
        for (String id : candidates) {
            Chunk c = lexical.chunk(id);
            if (c == null) {
                continue;                         // removed between the two lookups
            }
            double l = lex.getOrDefault(id, 0.0);
            double s = sem.getOrDefault(id, 0.0);
            double score = (1 - semanticWeight) * l + semanticWeight * s;
            if (score > 0) {
                hits.add(new ScoredChunk(c, score, l, s));
            }
        }
        hits.sort(Comparator.comparingDouble(ScoredChunk::score).reversed()
                .thenComparing(h -> h.chunk().id()));
        return hits.subList(0, Math.min(k, hits.size()));
    }

    public int size() {
        return lexical.size();
    }

    public int documentCount() {
        return lexical.documentCount();
    }

    public boolean semanticEnabled() {
        return vectors != null;
    }
}
