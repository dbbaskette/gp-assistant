package com.baskettecase.gpassistant.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

@Slf4j
@Service
@RequiredArgsConstructor
public class GreenplumVersionService {

    private final JdbcTemplate jdbcTemplate;
    private String cachedVersion;
    private String cachedProductName;
    private volatile boolean initialized = false;

    public void initializeVersionInfo() {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    try {
                        String versionString = jdbcTemplate.queryForObject("SELECT version()", String.class);
                        parseVersion(versionString);
                        log.info("Detected database: {} version {}", cachedProductName, cachedVersion);
                    } catch (Exception e) {
                        log.warn("Failed to detect Greenplum/PostgreSQL version, using default", e);
                        cachedVersion = "unknown";
                        cachedProductName = "PostgreSQL/Greenplum";
                    } finally {
                        initialized = true;
                    }
                }
            }
        }
    }

    /**
     * Parses the version string returned by SELECT version()
     * Example: "PostgreSQL 12.12 (Greenplum Database 7.1.0 build commit:...)"
     */
    private void parseVersion(String versionString) {
        if (versionString == null) {
            cachedVersion = "unknown";
            cachedProductName = "PostgreSQL/Greenplum";
            return;
        }

        log.debug("Raw version string: {}", versionString);

        // Check if it's Greenplum
        if (versionString.contains("Greenplum Database")) {
            cachedProductName = "Greenplum Database";
            // Extract version like "7.1.0"
            int startIdx = versionString.indexOf("Greenplum Database") + "Greenplum Database".length();
            int endIdx = versionString.indexOf(" ", startIdx + 1);
            if (endIdx == -1) {
                endIdx = versionString.indexOf(")", startIdx);
            }
            if (startIdx > 0 && endIdx > startIdx) {
                cachedVersion = versionString.substring(startIdx, endIdx).trim();
            } else {
                cachedVersion = "unknown";
            }
        } else if (versionString.startsWith("PostgreSQL")) {
            cachedProductName = "PostgreSQL";
            // Extract version like "14.5"
            String[] parts = versionString.split(" ");
            if (parts.length > 1) {
                cachedVersion = parts[1];
            } else {
                cachedVersion = "unknown";
            }
        } else {
            cachedProductName = "PostgreSQL/Greenplum";
            cachedVersion = "unknown";
        }
    }

    /**
     * Gets the detected version of Greenplum or PostgreSQL.
     * 
     * @return Version string (e.g., "7.1.0" or "14.5")
     */
    public String getVersion() {
        initializeVersionInfo();
        return cachedVersion != null ? cachedVersion : "unknown";
    }

    /**
     * Gets the product name (Greenplum Database or PostgreSQL).
     * 
     * @return Product name
     */
    public String getProductName() {
        initializeVersionInfo();
        return cachedProductName != null ? cachedProductName : "PostgreSQL/Greenplum";
    }

    /**
     * Gets a formatted string with product and version.
     * 
     * @return Formatted string like "Greenplum Database 7.1.0"
     */
    public String getFullVersion() {
        initializeVersionInfo();
        return String.format("%s %s", getProductName(), getVersion());
    }

    /**
     * Checks if the connected database is Greenplum (vs plain PostgreSQL).
     * 
     * @return true if Greenplum, false otherwise
     */
    public boolean isGreenplum() {
        initializeVersionInfo();
        return cachedProductName != null && cachedProductName.contains("Greenplum");
    }
}
