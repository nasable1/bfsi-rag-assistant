package com.narayansable.rag.index;

import com.narayansable.rag.ingest.Chunk;

/** A retrieval hit with its blended score and the two signals behind it, for explainability. */
public record ScoredChunk(Chunk chunk, double score, double bm25, double cosine) {
}
