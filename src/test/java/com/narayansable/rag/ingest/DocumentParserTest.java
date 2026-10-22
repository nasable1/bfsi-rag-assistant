package com.narayansable.rag.ingest;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    @Test
    void wordHeadingsBecomeSectionsAndTablesBecomeRows() throws Exception {
        byte[] docx;
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createStyles();
            var title = doc.createParagraph();
            title.setStyle("Title");
            title.createRun().setText("Demo Bank - Locker Policy");
            var h = doc.createParagraph();
            h.setStyle("Heading1");
            h.createRun().setText("Rent");
            doc.createParagraph().createRun().setText("Locker rent is payable annually in advance.");
            XWPFTable t = doc.createTable(2, 2);
            t.getRow(0).getCell(0).setText("Size");
            t.getRow(0).getCell(1).setText("Rent");
            t.getRow(1).getCell(0).setText("Medium");
            t.getRow(1).getCell(1).setText("Rs 4,000");
            doc.write(out);
            docx = out.toByteArray();
        }
        var parsed = parser.parse("lockers", "lockers.docx", docx);
        assertThat(parsed.title()).isEqualTo("Demo Bank - Locker Policy");
        assertThat(parsed.text()).contains("## Rent").contains("Medium | Rs 4,000");

        var chunks = new Chunker(700, 100).split(parsed);
        assertThat(chunks).extracting(Chunk::section).containsOnly("Rent");
        assertThat(chunks.get(0).page()).isZero();
    }

    @Test
    void pdfPagesAreNumbered() throws Exception {
        byte[] pdf = com.narayansable.rag.TestSupport.pdf(List.of("First page text about cheque books.",
                "Second page text about stop payments."));
        var parsed = parser.parse("cheques", "Cheque Services.pdf", pdf);
        assertThat(parsed.title()).isEqualTo("Cheque Services");
        var chunks = new Chunker(700, 100).split(parsed);
        assertThat(chunks).extracting(Chunk::page).containsExactly(1, 2);
        assertThat(chunks.get(1).text()).contains("stop payments");
    }

    @Test
    void pdfLinesAreRejoinedIntoParagraphs() {
        assertThat(DocumentParser.paragraphs("Payments above Rs 5 crore need\ntwo checkers.\nNext rule here"))
                .isEqualTo("Payments above Rs 5 crore need two checkers.\n\nNext rule here");
    }

    @Test
    void rejectsWhatItCannotRead() {
        assertThatThrownBy(() -> parser.parse("x", "x.pdf", "nope".getBytes()))
                .hasMessage("Not a readable PDF");
        assertThatThrownBy(() -> parser.parse("x", "x.docx", "nope".getBytes()))
                .hasMessageContaining("Word");
        assertThatThrownBy(() -> parser.parse("x", "x.txt", new byte[] {(byte) 0xC3, (byte) 0x28}))
                .hasMessageContaining("UTF-8");
        assertThatThrownBy(() -> parser.parse("x", "x.txt", "   ".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("no text");
        assertThatThrownBy(() -> parser.parse("x", "x.zip", new byte[] {1}))
                .hasMessageContaining("Unsupported");
    }

    @Test
    void idsAndTitlesComeFromFileNames() {
        assertThat(DocumentService.idFrom("Locker Rent (2026).pdf")).isEqualTo("locker-rent-2026");
        assertThat(DocumentService.idFrom("C:\\docs\\KYC_Policy.docx")).isEqualTo("kyc-policy");
        assertThat(DocumentService.idFrom("!.pdf")).isEqualTo("document");
        assertThat(DocumentService.idFrom("a".repeat(80) + ".md")).hasSize(60);
        assertThat(DocumentParser.titleFromFileName("cheque_services-v2.pdf", "x")).isEqualTo("cheque services v2");
    }
}
