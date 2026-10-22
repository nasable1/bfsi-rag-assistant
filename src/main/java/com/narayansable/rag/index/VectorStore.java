package com.narayansable.rag.index;

import com.narayansable.rag.ingest.Chunk;

import java.util.List;

/**
 * Where chunk embeddings live and are searched. {@link InMemoryVectorStore} keeps them in the JVM;
 * {@link PgVectorStore} keeps them in Postgres with a pgvector HNSW index, so they survive restarts
 * and scale past what fits in memory.
 */
public interface VectorStore {

    record Hit(String chunkId, double cosine) {
    }

    /** Embeds (where needed) and stores a document's chunks, replacing whatever it had before. */
    void replaceDocument(String documentId, List<Chunk> chunks);

    void removeDocument(String documentId);

    /** The {@code n} chunks most similar to the (unit-length) query vector, most similar first. */
    List<Hit> search(float[] query, int n);
}
