package com.narayansable.rag.ingest;

/** A source document: an id that citations point back to, a human title, and its text. */
public record Document(String id, String title, String text) {
}
