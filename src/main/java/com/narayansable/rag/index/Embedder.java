package com.narayansable.rag.index;

import java.util.List;

/** Turns text into a unit-length vector whose dot product with another is their semantic similarity. */
public interface Embedder {

    int dimension();

    float[] embed(String text);

    default List<float[]> embedAll(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }
}
