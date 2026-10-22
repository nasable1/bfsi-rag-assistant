package com.narayansable.rag.index;

import com.narayansable.rag.ingest.Chunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * In-memory hybrid lexical index: BM25 over word tokens, blended with TF-IDF cosine similarity over
 * words and adjacent word pairs.
 *
 * <p>Why two signals: BM25 rewards rare exact terms ("RTGS", "maker-checker"), which is what a
 * banker's question usually hinges on; the bigram cosine rewards phrasing that matches ("cut-off
 * time", "returned to the remitter") and is far less sensitive to document length. The blend is a
 * weighted sum of the cosine and a squashed BM25 ({@code s / (s + 2)}), so the score has a fixed
 * 0..1 scale and a single threshold can decide "not in the documents". Reciprocal rank fusion was
 * the alternative; it ranks well but gives no absolute score to refuse on.
 *
 * <p>The interface is deliberately small (add, search) so a vector store - pgvector, OpenSearch k-NN
 * - can replace it without touching the answer pipeline.
 */
@Component
public class HybridIndex {

    static final double K1 = 1.2;
    static final double B = 0.75;
    static final double BM25_WEIGHT = 0.6;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Integer> docFreq = new HashMap<>();       // word -> chunks containing it
    private final Map<String, Integer> featureFreq = new HashMap<>();   // word or bigram -> chunks
    private final Set<String> documents = new HashSet<>();
    private long totalLength;

    private record Entry(Chunk chunk, Map<String, Integer> tf, int length, Map<String, Integer> features) {
    }

    public void addAll(Collection<Chunk> chunks) {
        lock.writeLock().lock();
        try {
            for (Chunk c : chunks) {
                List<String> toks = Tokenizer.tokens(c.section() + " " + c.text());
                Map<String, Integer> tf = counts(toks);
                Map<String, Integer> feats = features(toks);
                entries.add(new Entry(c, tf, toks.size(), feats));
                tf.keySet().forEach(w -> docFreq.merge(w, 1, Integer::sum));
                feats.keySet().forEach(f -> featureFreq.merge(f, 1, Integer::sum));
                totalLength += toks.size();
                documents.add(c.documentId());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<ScoredChunk> search(String query, int k) {
        lock.readLock().lock();
        try {
            if (entries.isEmpty()) {
                return List.of();
            }
            List<String> q = Tokenizer.tokens(query);
            Map<String, Double> qVec = tfidf(features(q));
            double avgLen = (double) totalLength / entries.size();
            List<ScoredChunk> hits = new ArrayList<>();
            for (Entry e : entries) {
                double bm25 = 0;
                for (String w : new HashSet<>(q)) {
                    Integer f = e.tf().get(w);
                    if (f == null) {
                        continue;
                    }
                    double idf = Math.log(1 + (entries.size() - docFreq.get(w) + 0.5) / (docFreq.get(w) + 0.5));
                    bm25 += idf * f * (K1 + 1) / (f + K1 * (1 - B + B * e.length() / avgLen));
                }
                double cos = cosine(qVec, tfidf(e.features()));
                double score = BM25_WEIGHT * (bm25 / (bm25 + 2)) + (1 - BM25_WEIGHT) * cos;
                if (score > 0) {
                    hits.add(new ScoredChunk(e.chunk(), score, bm25, cos));
                }
            }
            hits.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
            return hits.subList(0, Math.min(k, hits.size()));
        } finally {
            lock.readLock().unlock();
        }
    }

    public int size() {
        return entries.size();
    }

    public int documentCount() {
        return documents.size();
    }

    private static Map<String, Integer> counts(List<String> toks) {
        Map<String, Integer> m = new HashMap<>();
        toks.forEach(t -> m.merge(t, 1, Integer::sum));
        return m;
    }

    private static Map<String, Integer> features(List<String> toks) {
        Map<String, Integer> m = counts(toks);
        for (int i = 0; i + 1 < toks.size(); i++) {
            m.merge(toks.get(i) + "_" + toks.get(i + 1), 1, Integer::sum);
        }
        return m;
    }

    private Map<String, Double> tfidf(Map<String, Integer> feats) {
        Map<String, Double> v = new HashMap<>();
        int n = entries.size();
        feats.forEach((f, c) -> v.put(f, (1 + Math.log(c)) * Math.log(1.0 + n / (double) (1 + featureFreq.getOrDefault(f, 0)))));
        return v;
    }

    private static double cosine(Map<String, Double> a, Map<String, Double> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        double dot = 0;
        for (var e : a.entrySet()) {
            Double y = b.get(e.getKey());
            if (y != null) {
                dot += e.getValue() * y;
            }
        }
        if (dot == 0) {
            return 0;
        }
        return dot / (norm(a) * norm(b));
    }

    private static double norm(Map<String, Double> v) {
        double s = 0;
        for (double x : v.values()) {
            s += x * x;
        }
        return Math.sqrt(s);
    }
}
