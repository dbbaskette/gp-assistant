package com.baskettecase.gpassistant.controller;

import com.baskettecase.gpassistant.DocsIngestor;
import com.baskettecase.gpassistant.service.GreenplumVersionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Objects;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final DocsIngestor docsIngestor;
    private final GreenplumVersionService versionService;

    public AdminController(DocsIngestor docsIngestor, GreenplumVersionService versionService) {
        this.docsIngestor = docsIngestor;
        this.versionService = versionService;
    }

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

    public static class IngestResponse {
        private final boolean success;
        private final String message;
        private final String error;

        public IngestResponse(boolean success, String message, String error) {
            this.success = success;
            this.message = message;
            this.error = error;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public String getError() {
            return error;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            IngestResponse that = (IngestResponse) o;
            return success == that.success &&
                    Objects.equals(message, that.message) &&
                    Objects.equals(error, that.error);
        }

        @Override
        public int hashCode() {
            return Objects.hash(success, message, error);
        }

        @Override
        public String toString() {
            return "IngestResponse{" +
                    "success=" + success +
                    ", message='" + message + '\'' +
                    ", error='" + error + '\'' +
                    '}';
        }
    }

    public static class DatabaseInfo {
        private String productName;
        private String version;
        private String fullVersion;
        private boolean isGreenplum;

        public DatabaseInfo() {
        }

        public String getProductName() {
            return productName;
        }

        public void setProductName(String productName) {
            this.productName = productName;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getFullVersion() {
            return fullVersion;
        }

        public void setFullVersion(String fullVersion) {
            this.fullVersion = fullVersion;
        }

        public boolean isGreenplum() {
            return isGreenplum;
        }

        public void setGreenplum(boolean greenplum) {
            isGreenplum = greenplum;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DatabaseInfo that = (DatabaseInfo) o;
            return isGreenplum == that.isGreenplum &&
                    Objects.equals(productName, that.productName) &&
                    Objects.equals(version, that.version) &&
                    Objects.equals(fullVersion, that.fullVersion);
        }

        @Override
        public int hashCode() {
            return Objects.hash(productName, version, fullVersion, isGreenplum);
        }

        @Override
        public String toString() {
            return "DatabaseInfo{" +
                    "productName='" + productName + '\'' +
                    ", version='" + version + '\'' +
                    ", fullVersion='" + fullVersion + '\'' +
                    ", isGreenplum=" + isGreenplum +
                    '}';
        }
    }
}
