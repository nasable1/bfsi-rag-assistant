package com.narayansable.rag.web;

import com.narayansable.rag.index.Access;
import com.narayansable.rag.ingest.DocumentParser;
import com.narayansable.rag.ingest.DocumentRepository;
import com.narayansable.rag.ingest.DocumentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Document management: every caller lists the documents it may retrieve; adding and removing needs ADMIN.
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    /** {@code accessGroups}: who may retrieve it; empty or absent = every caller. */
    public record IngestRequest(@NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,59}") String id,
                                @NotBlank @Size(max = 200_000) String markdown,
                                @Size(max = 10) List<@Size(max = 40) String> accessGroups) {
    }

    private final DocumentService documents;

    public DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    /** The documents this caller may retrieve (ADMIN: all of them). */
    @GetMapping
    public List<DocumentRepository.Summary> list(Authentication auth) {
        return documents.list(Callers.access(auth));
    }

    /** A Markdown document as JSON. */
    @PostMapping(consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentService.Ingested ingest(@Valid @RequestBody IngestRequest request, Authentication auth) {
        return documents.ingestMarkdown(request.id(), request.markdown(), auth.getName(),
                request.accessGroups() == null ? Set.of() : new LinkedHashSet<>(request.accessGroups()));
    }

    /** A PDF, Word (.docx), Markdown or text file; {@code id} defaults to one made from the file name. */
    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentService.Ingested upload(@RequestParam("file") MultipartFile file,
                                           @RequestParam(value = "id", required = false) String id,
                                           @RequestParam(value = "groups", required = false) String groups,
                                           Authentication auth) throws IOException {
        Set<String> access;
        try {
            access = Access.parse(groups);
        } catch (IllegalArgumentException e) {
            throw new DocumentParser.UnreadableDocumentException(e.getMessage());
        }
        return documents.ingestFile(id, file.getOriginalFilename(), file.getBytes(), auth.getName(), access);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        return documents.delete(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
