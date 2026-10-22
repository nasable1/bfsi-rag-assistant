package com.narayansable.rag.index;

import com.narayansable.rag.ingest.Chunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Exact (brute-force) cosine search over vectors held in memory - right for a corpus of thousands of chunks. */
public class InMemoryVectorStore implements VectorStore {

    private final Embedder embedder;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, Map<String, float[]>> byDocument = new LinkedHashMap<>();

    public InMemoryVectorStore(Embedder embedder) {
        this.embedder = embedder;
    }

    @Override
    public void replaceDocument(String documentId, List<Chunk> chunks) {
        // Embed outside the lock: it is the slow part, and searches can carry on meanwhile.
        List<float[]> vectors = embedder.embedAll(chunks.stream().map(InMemoryVectorStore::embeddingText).toList());
        Map<String, float[]> doc = new LinkedHashMap<>();
        for (int i = 0; i < chunks.size(); i++) {
            doc.put(chunks.get(i).id(), vectors.get(i));
        }
        lock.writeLock().lock();
        try {
            byDocument.put(documentId, doc);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void removeDocument(String documentId) {
        lock.writeLock().lock();
        try {
            byDocument.remove(documentId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<Hit> search(float[] query, int n) {
        PriorityQueue<Hit> best = new PriorityQueue<>(Comparator.comparingDouble(Hit::cosine));
        lock.readLock().lock();
        try {
            for (Map<String, float[]> doc : byDocument.values()) {
                for (var e : doc.entrySet()) {
                    best.add(new Hit(e.getKey(), dot(query, e.getValue())));
                    if (best.size() > n) {
                        best.poll();
                    }
                }
            }
        } finally {
            lock.readLock().unlock();
        }
        List<Hit> out = new ArrayList<>(best);
        out.sort(Comparator.comparingDouble(Hit::cosine).reversed());
        return out;
    }

    /** What gets embedded: the section heading carries meaning the chunk text often leaves implicit. */
    static String embeddingText(Chunk c) {
        return c.section() + ": " + c.text();
    }

    static double dot(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }
}
