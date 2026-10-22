package com.narayansable.rag.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits a document into overlapping chunks along paragraph boundaries.
 *
 * <p>Policy text is written in short numbered rules; cutting one in half loses its condition or its
 * exception. So chunks are built from whole paragraphs up to the size limit, a paragraph longer
 * than the limit is split on sentence ends, and each chunk repeats the tail of the previous one
 * ({@code overlap} characters) so a rule that straddles a boundary is still retrievable whole.
 * Every chunk remembers the heading it sits under.
 */
public final class Chunker {

    private static final Pattern HEADING_LINE = Pattern.compile("(?m)^(#{1,6}[ \\t]+\\S.*)$");
    private static final Pattern HEADING = Pattern.compile("#{1,6}[ \\t]+\\S.*");

    private final int size;
    private final int overlap;

    public Chunker(int size, int overlap) {
        if (overlap >= size) {
            throw new IllegalArgumentException("overlap must be smaller than the chunk size");
        }
        this.size = size;
        this.overlap = overlap;
    }

    public List<Chunk> split(Document doc) {
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String heading = doc.title();
        String chunkHeading = heading;
        // Every heading line becomes its own block, so text written straight under a heading (no
        // blank line between them) is kept and filed under that heading instead of being dropped.
        String text = HEADING_LINE.matcher(doc.text().replace("\r\n", "\n")).replaceAll("\n\n$1\n\n");
        for (String block : text.split("\\n\\s*\\n")) {
            String para = block.strip();
            if (para.isEmpty()) {
                continue;
            }
            if (HEADING.matcher(para).matches()) {
                heading = para.replaceFirst("^#+\\s*", "").strip();
                if (current.length() > 0) {               // a new section starts a new chunk
                    chunks.add(chunk(doc, chunkHeading, current.toString(), chunks.size()));
                    current.setLength(0);
                }
                chunkHeading = heading;
                continue;
            }
            for (String piece : pieces(para)) {
                if (current.length() > 0 && current.length() + piece.length() + 2 > size) {
                    chunks.add(chunk(doc, chunkHeading, current.toString(), chunks.size()));
                    String tail = tail(current.toString());
                    current.setLength(0);
                    current.append(tail);
                    chunkHeading = heading;
                }
                if (current.length() > 0) {
                    current.append("\n\n");
                }
                current.append(piece);
            }
        }
        if (!current.toString().isBlank()) {
            chunks.add(chunk(doc, chunkHeading, current.toString(), chunks.size()));
        }
        return chunks;
    }

    /** A paragraph as one piece, or split on sentence ends when it is longer than a chunk. */
    private List<String> pieces(String para) {
        if (para.length() <= size) {
            return List.of(para);
        }
        List<String> out = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        for (String sentence : para.split("(?<=[.!?])\\s+")) {
            if (b.length() > 0 && b.length() + sentence.length() + 1 > size) {
                out.add(b.toString());
                b.setLength(0);
            }
            if (b.length() > 0) {
                b.append(' ');
            }
            b.append(sentence);
        }
        if (b.length() > 0) {
            out.add(b.toString());
        }
        return out;
    }

    /** The last {@code overlap} characters, started on a word boundary. */
    private String tail(String text) {
        if (overlap == 0 || text.length() <= overlap) {
            return overlap == 0 ? "" : text;
        }
        String t = text.substring(text.length() - overlap);
        int space = t.indexOf(' ');
        return space >= 0 ? t.substring(space + 1) : t;
    }

    private static Chunk chunk(Document doc, String section, String text, int ordinal) {
        return new Chunk(doc.id() + "#" + ordinal, doc.id(), doc.title(), section, text.strip(), ordinal);
    }
}
