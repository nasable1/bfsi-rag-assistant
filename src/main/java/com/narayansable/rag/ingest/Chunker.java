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
 * Every chunk remembers the heading it sits under and, for paged sources, its page: chunks never
 * span a page break, so a citation's page number is exact.
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

    public List<Chunk> split(ParsedDocument doc) {
        List<Chunk> chunks = new ArrayList<>();
        String all = doc.text().replace("\r\n", "\n");
        boolean paged = all.indexOf(ParsedDocument.PAGE_BREAK) >= 0;
        String[] pages = all.split(String.valueOf(ParsedDocument.PAGE_BREAK), -1);
        String heading = doc.title();
        for (int p = 0; p < pages.length; p++) {
            int page = paged ? p + 1 : 0;
            StringBuilder current = new StringBuilder();
            String chunkHeading = heading;
            // Every heading line becomes its own block, so text written straight under a heading
            // (no blank line between them) is kept and filed under that heading.
            String text = HEADING_LINE.matcher(pages[p]).replaceAll("\n\n$1\n\n");
            for (String block : text.split("\\n\\s*\\n")) {
                String para = block.strip();
                if (para.isEmpty()) {
                    continue;
                }
                if (HEADING.matcher(para).matches()) {
                    heading = para.replaceFirst("^#+\\s*", "").strip();
                    if (current.length() > 0) {               // a new section starts a new chunk
                        chunks.add(chunk(doc, chunkHeading, current.toString(), chunks.size(), page));
                        current.setLength(0);
                    }
                    chunkHeading = heading;
                    continue;
                }
                for (String piece : pieces(para)) {
                    if (current.length() > 0 && current.length() + piece.length() + 2 > size) {
                        chunks.add(chunk(doc, chunkHeading, current.toString(), chunks.size(), page));
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
                chunks.add(chunk(doc, chunkHeading, current.toString(), chunks.size(), page));
            }
        }
        return chunks;
    }

    /** A paragraph as one piece, or split on sentence ends (then on spaces) when longer than a chunk. */
    private List<String> pieces(String para) {
        if (para.length() <= size) {
            return List.of(para);
        }
        List<String> out = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        for (String sentence : para.split("(?<=[.!?])\\s+")) {
            for (String part : hardSplit(sentence)) {
                if (b.length() > 0 && b.length() + part.length() + 1 > size) {
                    out.add(b.toString());
                    b.setLength(0);
                }
                if (b.length() > 0) {
                    b.append(' ');
                }
                b.append(part);
            }
        }
        if (b.length() > 0) {
            out.add(b.toString());
        }
        return out;
    }

    /** A "sentence" longer than a chunk (a table row, text with no full stops) is cut on spaces. */
    private List<String> hardSplit(String sentence) {
        if (sentence.length() <= size) {
            return List.of(sentence);
        }
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < sentence.length()) {
            int end = Math.min(start + size, sentence.length());
            if (end < sentence.length()) {
                int space = sentence.lastIndexOf(' ', end);
                if (space > start) {
                    end = space;
                }
            }
            out.add(sentence.substring(start, end).strip());
            start = end;
        }
        return out;
    }

    /** The last {@code overlap} characters, started on a word boundary. */
    private String tail(String text) {
        if (overlap == 0) {
            return "";
        }
        if (text.length() <= overlap) {
            return text;
        }
        String t = text.substring(text.length() - overlap);
        int space = t.indexOf(' ');
        return space >= 0 ? t.substring(space + 1) : t;
    }

    private static Chunk chunk(ParsedDocument doc, String section, String text, int ordinal, int page) {
        return new Chunk(doc.id() + "#" + ordinal, doc.id(), doc.title(), section, text.strip(), ordinal, page);
    }
}
