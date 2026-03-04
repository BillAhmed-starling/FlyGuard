package com.flywaysafety.migration;

import com.flywaysafety.model.MigrationFile;
import org.flywaydb.core.Flyway;

import java.nio.file.Path;
import java.util.List;

/**
 * Applies baseline migrations (all migrations *except* the new PR ones)
 * against the target database using Flyway.
 *
 * Strategy: compute the highest version among existing (non-new) migrations
 * and pass .target(version) to Flyway so it runs only up to that version,
 * naturally excluding newer PR migrations.
 */
public class FlywayRunner {

    private final String jdbcUrl;
    private final String username;
    private final String password;

    public FlywayRunner(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    /**
     * Runs all migrations found in migrationsDir up to and including
     * the highest version that is NOT in newMigrations.
     *
     * @param migrationsDir directory containing all migration files
     * @param newMigrations migrations introduced in the current PR (to be excluded)
     */
    public void runBaseline(Path migrationsDir, List<MigrationFile> newMigrations) {
        String highestExistingVersion = resolveHighestExistingVersion(migrationsDir, newMigrations);

        Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations("filesystem:" + migrationsDir.toAbsolutePath())
                .target(highestExistingVersion != null ? highestExistingVersion : "0")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    /**
     * Discovers all migration files in the directory and returns the highest
     * version string that is not present in newMigrations.
     */
    private String resolveHighestExistingVersion(Path migrationsDir, List<MigrationFile> newMigrations) {
        MigrationDiscovery discovery = new MigrationDiscovery(migrationsDir);
        List<MigrationFile> allMigrations;
        try {
            allMigrations = discovery.discoverAll();
        } catch (Exception e) {
            throw new RuntimeException("Failed to discover migrations: " + e.getMessage(), e);
        }

        java.util.Set<String> newVersions = new java.util.HashSet<>();
        for (MigrationFile m : newMigrations) {
            newVersions.add(m.version());
        }

        MigrationFile highest = null;
        for (MigrationFile m : allMigrations) {
            if (!newVersions.contains(m.version())) {
                if (highest == null || m.compareTo(highest) > 0) {
                    highest = m;
                }
            }
        }

        return highest != null ? highest.version() : null;
    }
}
