package com.flywaysafety.migration;

import com.flywaysafety.model.MigrationFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers new migration files introduced in the current PR by shelling out to:
 *   git diff <baseBranch> --name-only --diff-filter=A
 *
 * Filters results to files inside migrationsDir matching V*.sql or R*.sql.
 * Sorts by Flyway version for deterministic processing order.
 */
public class MigrationDiscovery {

    // Matches V1__desc.sql, V1.2.3__desc.sql, R__desc.sql
    private static final Pattern MIGRATION_PATTERN = Pattern.compile("^(V([0-9._]+)|R)__.*\\.sql$", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSION_EXTRACTOR = Pattern.compile("^V([0-9._]+)__", Pattern.CASE_INSENSITIVE);

    private final Path migrationsDir;

    public MigrationDiscovery(Path migrationsDir) {
        this.migrationsDir = migrationsDir;
    }

    /**
     * Returns new migration files (added in this PR) relative to baseBranch.
     */
    public List<MigrationFile> discoverNewMigrations(String baseBranch) throws IOException, InterruptedException {
        List<String> addedFiles = gitDiffAddedFiles(baseBranch);
        return resolveMigrations(addedFiles);
    }

    /**
     * Returns ALL migration files found in migrationsDir (regardless of git status).
     * Used by FlywayRunner to enumerate existing migrations.
     */
    public List<MigrationFile> discoverAll() throws IOException {
        if (!Files.exists(migrationsDir)) return Collections.emptyList();

        List<MigrationFile> result = new ArrayList<>();
        try (var stream = Files.list(migrationsDir)) {
            stream.filter(p -> {
                String name = p.getFileName().toString();
                return MIGRATION_PATTERN.matcher(name).matches();
            }).forEach(p -> {
                MigrationFile mf = toMigrationFile(p);
                if (mf != null) result.add(mf);
            });
        }
        Collections.sort(result);
        return result;
    }

    private List<String> gitDiffAddedFiles(String baseBranch) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "git", "diff", baseBranch, "--name-only", "--diff-filter=A"
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new IOException("git diff failed (exit " + exitCode + "): " + output.trim());
        }

        List<String> files = new ArrayList<>();
        for (String line : output.split("\n")) {
            if (!line.isBlank()) files.add(line.trim());
        }
        return files;
    }

    private List<MigrationFile> resolveMigrations(List<String> gitPaths) throws IOException {
        Path absDir = migrationsDir.toAbsolutePath().normalize();
        List<MigrationFile> result = new ArrayList<>();

        for (String gitPath : gitPaths) {
            Path candidate = Path.of(gitPath).toAbsolutePath().normalize();
            // Also try resolving relative to cwd
            if (!candidate.startsWith(absDir)) {
                candidate = Path.of(System.getProperty("user.dir")).resolve(gitPath).normalize();
            }
            if (!candidate.startsWith(absDir)) continue;

            String filename = candidate.getFileName().toString();
            if (!MIGRATION_PATTERN.matcher(filename).matches()) continue;

            if (!Files.exists(candidate)) {
                System.err.println("[MigrationDiscovery] File not found on disk: " + candidate);
                continue;
            }

            MigrationFile mf = toMigrationFile(candidate);
            if (mf != null) result.add(mf);
        }

        Collections.sort(result);
        return result;
    }

    private MigrationFile toMigrationFile(Path path) {
        String filename = path.getFileName().toString();
        Matcher m = VERSION_EXTRACTOR.matcher(filename);
        String version = m.find() ? m.group(1) : null; // null for R__ repeatable migrations

        try {
            String sql = Files.readString(path);
            return new MigrationFile(version, filename, path, sql);
        } catch (IOException e) {
            System.err.println("[MigrationDiscovery] Could not read " + path + ": " + e.getMessage());
            return null;
        }
    }
}
