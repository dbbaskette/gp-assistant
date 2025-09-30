package com.baskettecase.gpassistant;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.ParagraphPdfDocumentReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class DocsIngestor {

    private final VectorStore vectorStore;
    private final MeterRegistry meterRegistry;

    @Value("${app.docs.pdf-url}")
    private String pdfUrl;

    @Value("${app.docs.ingest-on-startup:false}")
    private boolean ingestOnStartup;

    private Counter ingestSuccessCounter;
    private Counter ingestFailureCounter;
    private Timer ingestTimer;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        initializeMetrics();
        
        if (ingestOnStartup) {
            log.info("Auto-ingest enabled, starting document ingestion from: {}", pdfUrl);
            try {
                ingest();
            } catch (Exception e) {
                log.error("Failed to auto-ingest documents on startup", e);
                ingestFailureCounter.increment();
            }
        } else {
            log.info("Auto-ingest disabled. Call /api/admin/ingest to manually trigger ingestion.");
        }
    }

    private void initializeMetrics() {
        ingestSuccessCounter = Counter.builder("gp_assistant.docs.ingest.success")
                .description("Number of successful document ingestions")
                .register(meterRegistry);
        
        ingestFailureCounter = Counter.builder("gp_assistant.docs.ingest.failure")
                .description("Number of failed document ingestions")
                .register(meterRegistry);
        
        ingestTimer = Timer.builder("gp_assistant.docs.ingest.duration")
                .description("Time taken to ingest documents")
                .register(meterRegistry);
    }

    /**
     * Ingests the Greenplum documentation PDF into the vector store.
     * Downloads the PDF, processes it into chunks, and stores embeddings.
     * 
     * @throws IOException if download or processing fails
     */
    public void ingest() throws IOException {
        Timer.Sample sample = Timer.start(meterRegistry);
        
        try {
            log.info("Starting document ingestion from URL: {}", pdfUrl);
            
            // Download PDF
            Path tmp = downloadPdf(pdfUrl);
            log.info("Downloaded PDF to temporary file: {}", tmp);
            
            // Read and parse PDF
            var reader = new ParagraphPdfDocumentReader(new FileSystemResource(tmp.toFile()));
            List<Document> docs = reader.read();
            log.info("Extracted {} documents from PDF", docs.size());
            
            // Split documents into smaller chunks with token-based splitting
            TokenTextSplitter splitter = new TokenTextSplitter(
                    500,  // default chunk size (tokens)
                    100,  // overlap (tokens)
                    5,    // min chunk size
                    10000, // max chunk size
                    true  // keep separator
            );
            
            List<Document> chunks = docs.stream()
                    .flatMap(doc -> splitter.split(doc).stream())
                    .map(withMeta(pdfUrl))
                    .collect(Collectors.toList());
            
            log.info("Split into {} chunks after token-based splitting", chunks.size());
            
            // Store in vector database
            vectorStore.add(chunks);
            log.info("Successfully ingested {} chunks into vector store", chunks.size());
            
            // Clean up temporary file
            Files.deleteIfExists(tmp);
            log.debug("Cleaned up temporary file: {}", tmp);
            
            ingestSuccessCounter.increment();
            sample.stop(ingestTimer);
            
        } catch (IOException e) {
            log.error("Failed to ingest documents from URL: {}", pdfUrl, e);
            ingestFailureCounter.increment();
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error during document ingestion", e);
            ingestFailureCounter.increment();
            throw new IOException("Unexpected error during ingestion", e);
        }
    }

    /**
     * Downloads a PDF from the given URL to a temporary file.
     * 
     * @param url The URL to download from
     * @return Path to the temporary file
     * @throws IOException if download fails
     */
    private Path downloadPdf(String url) throws IOException {
        Path tmp = Files.createTempFile("greenplum-db", ".pdf");
        try (var in = new URL(url).openStream()) {
            long bytesDownloaded = Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            log.debug("Downloaded {} bytes from {}", bytesDownloaded, url);
            return tmp;
        } catch (IOException e) {
            log.error("Failed to download PDF from URL: {}", url, e);
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    /**
     * Adds metadata to documents including source URL and version information.
     * 
     * @param source The source URL of the document
     * @return Function to add metadata to a document
     */
    private Function<Document, Document> withMeta(String source) {
        return d -> {
            Map<String, Object> m = new HashMap<>(d.getMetadata());
            m.put("source", source);
            m.put("doc_type", "greenplum_manual");
            m.put("ingested_at", System.currentTimeMillis());
            return new Document(d.getText(), m);
        };
    }
}