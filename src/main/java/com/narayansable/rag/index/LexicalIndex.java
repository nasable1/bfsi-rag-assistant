package com.narayansable.rag.index;

import com.narayansable.rag.ingest.Chunk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * In-memory lexical index: BM25 over word tokens, blended with TF-IDF cosine similarity over words
 * and adjacent word pairs, into one 0..1 score per chunk.
 *
 * <p>Why two signals: BM25 rewards rare exact terms ("RTGS", "maker-checker"), which is what a
 * banker's question usually hinges on; the bigram cosine rewards phrasing that matches ("cut-off
 * time", "returned to the remitter") and is far less sensitive to document length. The blend is a
 * weighted sum of the cosine and a squashed BM25 ({@code s / (s + 2)}), so the score has a fixed
 * 0..1 scale. {@link Retriever} combines it with semantic similarity.
 */
public class LexicalIndex {

    static final double K1 = 1.2;
    static final double B = 0.75;
    static final double BM25_WEIGHT = 0.6;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Integer> docFreq = new HashMap<>();       // word -> chunks containing it
    private final Map<String, Integer> featureFreq = new HashMap<>();   // word or bigram -> chunks
    private final Map<String, Entry> byId = new HashMap<>();
    private final Map<String, Integer> documents = new HashMap<>();   // document id -> chunks
    private long totalLength;

    private record Entry(Chunk chunk, Map<String, Integer> tf, int length, Map<String, Integer> features) {
    }

    /**
     * Swaps a document's chunks for new ones in one step, so re-uploading a document updates it
     * instead of indexing it twice (duplicate chunks would crowd the top-k and skew term statistics).
     */
    public void replaceDocument(String documentId, Collection<Chunk> chunks) {
        lock.writeLock().lock();
        try {
            removeLocked(documentId);
            chunks.forEach(this::add);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void add(Chunk c) {
        List<String> toks = Tokenizer.tokens(c.section() + " " + c.text());
        Map<String, Integer> tf = counts(toks);
        Map<String, Integer> feats = features(toks);
        Entry entry = new Entry(c, tf, toks.size(), feats);
        entries.add(entry);
        byId.put(c.id(), entry);
        tf.keySet().forEach(w -> docFreq.merge(w, 1, Integer::sum));
        feats.keySet().forEach(f -> featureFreq.merge(f, 1, Integer::sum));
        totalLength += toks.size();
        documents.merge(c.documentId(), 1, Integer::sum);
    }

    public void removeDocument(String documentId) {
        lock.writeLock().lock();
        try {
            removeLocked(documentId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void removeLocked(String documentId) {
        if (documents.remove(documentId) == null) {
            return;
        }
        var it = entries.iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (!e.chunk().documentId().equals(documentId)) {
                continue;
            }
            it.remove();
            byId.remove(e.chunk().id());
            e.tf().keySet().forEach(w -> docFreq.computeIfPresent(w, (k, n) -> n > 1 ? n - 1 : null));
            e.features().keySet().forEach(f -> featureFreq.computeIfPresent(f, (k, n) -> n > 1 ? n - 1 : null));
            totalLength -= e.length();
        }
    }

    /** Lexical score (0..1) of every chunk that shares at least one term with the query. */
    public Map<String, Double> scores(String query) {
        lock.readLock().lock();
        try {
            Map<String, Double> out = new HashMap<>();
            if (entries.isEmpty()) {
                return out;
            }
            List<String> q = Tokenizer.tokens(query);
            Map<String, Double> qVec = tfidf(features(q));
            double qNorm = norm(qVec);
            double avgLen = (double) totalLength / entries.size();
            Set<String> qTerms = new HashSet<>(q);
            for (Entry e : entries) {
                double bm25 = 0;
                for (String w : qTerms) {
                    Integer f = e.tf().get(w);
                    if (f == null) {
                        continue;
                    }
                    double idf = Math.log(1 + (entries.size() - docFreq.get(w) + 0.5) / (docFreq.get(w) + 0.5));
                    bm25 += idf * f * (K1 + 1) / (f + K1 * (1 - B + B * e.length() / avgLen));
                }
                if (bm25 == 0) {
                    continue;                      // no shared word, so no shared bigram either
                }
                double cos = cosine(qVec, qNorm, tfidf(e.features()));
                out.put(e.chunk().id(), BM25_WEIGHT * (bm25 / (bm25 + 2)) + (1 - BM25_WEIGHT) * cos);
            }
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** The chunk with this id, or null when it is not (or no longer) indexed. */
    public Chunk chunk(String id) {
        lock.readLock().lock();
        try {
            Entry e = byId.get(id);
            return e == null ? null : e.chunk();
        } finally {
            lock.readLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return entries.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public int documentCount() {
        lock.readLock().lock();
        try {
            return documents.size();
        } finally {
            lock.readLock().unlock();
        }
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

    private static double cosine(Map<String, Double> a, double aNorm, Map<String, Double> b) {
        if (a.isEmpty() || b.isEmpty() || aNorm == 0) {
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
        return dot / (aNorm * norm(b));
    }

    private static double norm(Map<String, Double> v) {
        double s = 0;
        for (double x : v.values()) {
            s += x * x;
        }
        return Math.sqrt(s);
    }
}
