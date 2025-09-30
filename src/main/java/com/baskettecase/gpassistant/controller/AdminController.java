package com.baskettecase.gpassistant.controller;

import com.baskettecase.gpassistant.DocsIngestor;
import com.baskettecase.gpassistant.service.GreenplumVersionService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final DocsIngestor docsIngestor;
    private final GreenplumVersionService versionService;

    /**
     * Manually trigger document ingestion.
     * This endpoint downloads and processes the Greenplum documentation PDF.
     */
    @PostMapping("/ingest")
    public ResponseEntity<IngestResponse> ingest() {
        log.info("Manual document ingestion triggered");
        
        try {
            docsIngestor.ingest();
            log.info("Document ingestion completed successfully");
            return ResponseEntity.ok(new IngestResponse(true, "Document ingestion completed successfully", null));
            
        } catch (IOException e) {
            log.error("Document ingestion failed", e);
            return ResponseEntity.internalServerError()
                    .body(new IngestResponse(false, "Document ingestion failed", e.getMessage()));
        }
    }

    /**
     * Get information about the connected Greenplum/PostgreSQL database.
     */
    @GetMapping("/database-info")
    public ResponseEntity<DatabaseInfo> getDatabaseInfo() {
        log.debug("Database info requested");
        
        DatabaseInfo info = new DatabaseInfo();
        info.setProductName(versionService.getProductName());
        info.setVersion(versionService.getVersion());
        info.setFullVersion(versionService.getFullVersion());
        info.setGreenplum(versionService.isGreenplum());
        
        return ResponseEntity.ok(info);
    }

    @Data
    @lombok.AllArgsConstructor
    public static class IngestResponse {
        private final boolean success;
        private final String message;
        private final String error;
    }

    @Data
    @lombok.NoArgsConstructor
    public static class DatabaseInfo {
        private String productName;
        private String version;
        private String fullVersion;
        private boolean isGreenplum;
    }
}
