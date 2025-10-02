package com.baskettecase.gpassistant;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.core.io.FileSystemResource;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class DocsIngestor {

    private static final Logger log = LoggerFactory.getLogger(DocsIngestor.class);

    private final VectorStore vectorStore;
    private final MeterRegistry meterRegistry;

    public DocsIngestor(VectorStore vectorStore, MeterRegistry meterRegistry) {
        this.vectorStore = vectorStore;
        this.meterRegistry = meterRegistry;
    }

    @Value("${app.docs.pdf-url}")
    private String pdfUrl;

    @Value("${app.docs.ingest-on-startup:false}")
    private boolean ingestOnStartup;

    @Value("${app.docs.batch-size:100}")
    private int batchSize;

    @Value("${app.docs.pages-per-batch:50}")
    private int pagesPerBatch;

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
            
            // Read and parse PDF using PagePdfDocumentReader for better performance
            log.info("Parsing downloaded PDF into documents...");
            long parseStart = System.currentTimeMillis();

            // Configure PDF reader for better performance
            PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                    .withPagesPerDocument(pagesPerBatch) // Process multiple pages at once for better performance
                    .build();

            var reader = new PagePdfDocumentReader(new FileSystemResource(tmp.toFile()), config);
            List<Document> docs = reader.read();

            long parseTime = System.currentTimeMillis() - parseStart;
            log.info("Extracted {} pages from PDF in {} seconds", docs.size(), parseTime / 1000.0);
            
            // Split documents into smaller chunks with token-based splitting
            log.info("Splitting {} documents into token-sized chunks...", docs.size());
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

            // Store in vector database with batch processing
            log.info("Generating embeddings and storing {} chunks in pgvector...", chunks.size());

            String chatBaseUrl = resolveConfigValue("spring.ai.openai.base-url", "OPENAI_BASE_URL",
                    resolveConfigValue(null, "LOCAL_MODEL_BASE_URL", "http://127.0.0.1:1234"));
            String embeddingsBaseUrl = resolveConfigValue("spring.ai.openai.embedding.base-url", "OPENAI_EMBEDDING_BASE_URL",
                    chatBaseUrl);
            String embeddingsPath = resolveConfigValue("spring.ai.openai.embedding.embeddings-path", "OPENAI_EMBEDDINGS_PATH",
                    "/v1/embeddings");

            log.info("Chat base URL: {}", chatBaseUrl);
            log.info("Embedding endpoint: {}{}", embeddingsBaseUrl, embeddingsPath);

            // Process in batches to avoid timeout and memory issues
            int totalChunks = chunks.size();
            int processedChunks = 0;
            int failedChunks = 0;

            // Use smaller batch size initially to debug the issue
            int effectiveBatchSize = Math.min(5, batchSize); // Start with 5 chunks at a time
            log.info("Using batch size of {} chunks (reduced for debugging)", effectiveBatchSize);

            for (int i = 0; i < totalChunks; i += effectiveBatchSize) {
                int end = Math.min(i + effectiveBatchSize, totalChunks);
                List<Document> batch = chunks.subList(i, end);

                try {
                    log.info("Processing batch {}/{} (chunks {}-{} of {})",
                            (i/effectiveBatchSize) + 1,
                            (totalChunks + effectiveBatchSize - 1) / effectiveBatchSize,
                            i + 1, end, totalChunks);

                    long batchStart = System.currentTimeMillis();

                    // Log the actual text being processed (first 100 chars)
                    for (int j = 0; j < Math.min(2, batch.size()); j++) {
                        String preview = batch.get(j).getText();
                        if (preview.length() > 100) {
                            preview = preview.substring(0, 100) + "...";
                        }
                        log.debug("Chunk {} preview: {}", i + j + 1, preview);
                    }

                    // Try to add the batch to vector store
                    log.debug("Calling vectorStore.add() with {} documents", batch.size());
                    vectorStore.add(batch);

                    long batchTime = System.currentTimeMillis() - batchStart;

                    processedChunks += batch.size();
                    log.info("Successfully stored batch of {} chunks in {} seconds. Total progress: {}/{}",
                            batch.size(), batchTime / 1000.0, processedChunks, totalChunks);

                    // Small delay between batches to avoid overwhelming the embedding service
                    if (i + effectiveBatchSize < totalChunks) {
                        Thread.sleep(100); // Reduced delay for smaller batches
                    }
                } catch (Exception e) {
                    failedChunks += batch.size();
                    log.error("Failed to process batch {}/{} (chunks {}-{}): {}",
                            (i/effectiveBatchSize) + 1,
                            (totalChunks + effectiveBatchSize - 1) / effectiveBatchSize,
                            i + 1, end, e.getMessage());
                    log.error("Full exception: ", e);

                    // Try processing one at a time if batch fails
                    if (batch.size() > 1) {
                        log.info("Retrying failed batch one document at a time...");
                        for (int j = 0; j < batch.size(); j++) {
                            try {
                                vectorStore.add(List.of(batch.get(j)));
                                processedChunks++;
                                failedChunks--;
                                log.debug("Successfully stored single chunk {}", i + j + 1);
                            } catch (Exception e2) {
                                log.error("Failed to store single chunk {}: {}", i + j + 1, e2.getMessage());
                            }
                        }
                    }

                    // Continue with next batch
                    if (failedChunks > 100) {
                        throw new RuntimeException("Too many failed chunks (" + failedChunks + "), aborting ingestion", e);
                    }
                }
            }

            if (failedChunks > 0) {
                log.warn("Ingestion completed with {} failed chunks. Successfully processed {}/{} chunks",
                        failedChunks, processedChunks, totalChunks);
            } else {
                log.info("Successfully ingested all {} chunks into vector store", processedChunks);
            }
            
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
        try (var in = URI.create(url).toURL().openStream()) {
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

    private String resolveConfigValue(String systemKey, String envKey, String defaultValue) {
        String value = null;

        if (StringUtils.hasText(systemKey)) {
            value = System.getProperty(systemKey);
        }

        if (!StringUtils.hasText(value) && StringUtils.hasText(envKey)) {
            value = System.getenv(envKey);
        }

        return StringUtils.hasText(value) ? value : defaultValue;
    }
}
