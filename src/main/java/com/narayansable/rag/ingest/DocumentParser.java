package com.narayansable.rag.ingest;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Turns an uploaded file into text the chunker understands: Markdown headings for structure and a
 * form feed between pages. Markdown and plain text pass through; PDF text is extracted page by page
 * (so citations can name the page); Word headings become Markdown headings and tables become one
 * line per row.
 *
 * <p>Uploads are untrusted, so the parser rejects encrypted PDFs and caps the page count - a
 * policy document is not 2,000 pages, and a parser should not be the way to exhaust the server.
 */
@Component
public class DocumentParser {

    public static final int MAX_PDF_PAGES = 500;
    private static final Pattern WORD_HEADING = Pattern.compile("(?i)heading\\s*([1-6])");

    static {
        // A .docx is a zip: cap what one entry may expand to, so a 20 MB "zip bomb" cannot inflate to
        // gigabytes in memory. (POI already rejects suspicious compression ratios.)
        ZipSecureFile.setMaxEntrySize(100L * 1024 * 1024);
        ZipSecureFile.setMaxTextSize(20L * 1024 * 1024);
    }

    /** The upload could not be read as a document; the message is safe to show to the caller. */
    public static class UnreadableDocumentException extends RuntimeException {
        public UnreadableDocumentException(String message) {
            super(message);
        }
    }

    public ParsedDocument parse(String id, String fileName, byte[] bytes) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String text;
        if (name.endsWith(".pdf")) {
            text = pdf(bytes);
        } else if (name.endsWith(".docx")) {
            text = docx(bytes);
        } else if (name.endsWith(".md") || name.endsWith(".markdown") || name.endsWith(".txt")) {
            text = utf8(bytes);
        } else {
            throw new UnreadableDocumentException("Unsupported file type - upload .pdf, .docx, .md or .txt");
        }
        return markdown(id, text, titleFromFileName(fileName, id));
    }

    /** A Markdown (or plain-text) document; the first {@code # Heading} is its title. */
    public ParsedDocument markdown(String id, String text, String fallbackTitle) {
        if (text.isBlank()) {
            throw new UnreadableDocumentException("The document contains no text"
                    + " (a scanned PDF needs OCR before it can be uploaded)");
        }
        String title = text.lines().filter(l -> l.startsWith("# ")).findFirst()
                .map(l -> l.substring(2).strip()).orElse(fallbackTitle);
        return new ParsedDocument(id, title, text);
    }

    private static String pdf(byte[] bytes) {
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            if (pdf.isEncrypted()) {
                throw new UnreadableDocumentException("Encrypted PDFs are not supported");
            }
            int pages = pdf.getNumberOfPages();
            if (pages > MAX_PDF_PAGES) {
                throw new UnreadableDocumentException("PDF has " + pages + " pages; the limit is " + MAX_PDF_PAGES);
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            StringBuilder out = new StringBuilder();
            for (int p = 1; p <= pages; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                if (p > 1) {
                    out.append(ParsedDocument.PAGE_BREAK);
                }
                out.append(paragraphs(stripper.getText(pdf)));
            }
            return out.toString();
        } catch (IOException e) {
            throw new UnreadableDocumentException("Not a readable PDF");
        }
    }

    /**
     * PDF text arrives one visual line per line. Lines are re-joined into paragraphs, and a blank
     * line is kept where a paragraph ends (a line ending in a full stop, or followed by a gap).
     */
    static String paragraphs(String pageText) {
        StringBuilder out = new StringBuilder();
        for (String raw : pageText.replace("\r\n", "\n").split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                out.append("\n\n");
                continue;
            }
            if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                out.append(' ');
            }
            out.append(line);
            if (line.endsWith(".") || line.endsWith(":")) {
                out.append("\n\n");
            }
        }
        return out.toString().replaceAll("\n{3,}", "\n\n").strip();
    }

    private static String docx(byte[] bytes) {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder out = new StringBuilder();
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph p) {
                    String text = p.getText().strip();
                    if (text.isEmpty()) {
                        continue;
                    }
                    out.append(headingPrefix(p)).append(text).append("\n\n");
                } else if (element instanceof XWPFTable t) {
                    for (XWPFTableRow row : t.getRows()) {
                        out.append(row.getTableCells().stream().map(c -> c.getText().strip())
                                .collect(Collectors.joining(" | "))).append("\n\n");
                    }
                }
            }
            return out.toString();
        } catch (IOException | RuntimeException e) {
            if (e instanceof UnreadableDocumentException u) {
                throw u;
            }
            throw new UnreadableDocumentException("Not a readable Word (.docx) document");
        }
    }

    /** "Title" becomes the document title (#); "Heading 1".."Heading 5" become ## .. ######. */
    private static String headingPrefix(XWPFParagraph p) {
        String style = p.getStyleID() == null ? "" : p.getStyleID();
        if (p.getStyle() != null && p.getDocument().getStyles() != null
                && p.getDocument().getStyles().getStyle(p.getStyle()) != null) {
            style = style + " " + p.getDocument().getStyles().getStyle(p.getStyle()).getName();
        }
        if (style.toLowerCase(Locale.ROOT).contains("title")) {
            return "# ";
        }
        Matcher m = WORD_HEADING.matcher(style);
        if (m.find()) {
            int level = Math.min(Integer.parseInt(m.group(1)) + 1, 6);
            return "#".repeat(level) + " ";
        }
        return "";
    }

    private static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString().replace("﻿", "");
        } catch (CharacterCodingException e) {
            throw new UnreadableDocumentException("Text files must be UTF-8");
        }
    }

    static String titleFromFileName(String fileName, String fallback) {
        if (fileName == null || fileName.isBlank()) {
            return fallback;
        }
        String base = fileName.replaceAll("^.*[/\\\\]", "").replaceFirst("\\.[^.]+$", "");
        return base.isBlank() ? fallback : base.replaceAll("[-_]+", " ").strip();
    }
}
