package com.narayansable.rag.ingest;

/**
 * A retrievable slice of a document. {@code section} is the nearest heading above the slice and
 * {@code page} the 1-based page it came from (0 when the source has no pages, e.g. Markdown), so a
 * citation can say exactly where in the document the answer came from.
 */
public record Chunk(String id, String documentId, String documentTitle, String section, String text, int ordinal,
                    int page) {
}
