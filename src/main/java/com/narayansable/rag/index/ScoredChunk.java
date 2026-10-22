package com.narayansable.rag.index;

import com.narayansable.rag.ingest.Chunk;

/**
 * A retrieval hit with its blended 0..1 score and the two signals behind it - the lexical score
 * and the semantic (embedding cosine) similarity - for explainability.
 */
public record ScoredChunk(Chunk chunk, double score, double lexical, double semantic) {
}
