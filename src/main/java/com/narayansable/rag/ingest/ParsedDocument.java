package com.narayansable.rag.ingest;

/**
 * A source document as plain text: an id that citations point back to, a human title, and its
 * text with Markdown-style headings. Page breaks, when the source has pages, are form feeds
 * ({@code \f}) - the chunker numbers pages from them.
 */
public record ParsedDocument(String id, String title, String text) {

    public static final char PAGE_BREAK = '\f';
}
