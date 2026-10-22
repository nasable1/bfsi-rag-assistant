package com.narayansable.rag.ingest;

/**
 * A retrievable slice of a document. {@code section} is the nearest Markdown heading above the
 * slice, so a citation can say where in the document the answer came from.
 */
public record Chunk(String id, String documentId, String documentTitle, String section, String text, int ordinal) {
}
