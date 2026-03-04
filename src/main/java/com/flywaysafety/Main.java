package com.flywaysafety;

import com.flywaysafety.analysis.AnalysisResult;
import com.flywaysafety.analysis.LLMAnalyzer;
import com.flywaysafety.analysis.RuleBasedAnalyzer;
import com.flywaysafety.migration.FlywayRunner;
import com.flywaysafety.migration.MigrationDiscovery;
import com.flywaysafety.model.MigrationFile;
import com.flywaysafety.model.SafetyRating;
import com.flywaysafety.model.SchemaContext;
import com.flywaysafety.report.MarkdownReportGenerator;
import com.flywaysafety.schema.RowCountImporter;
import com.flywaysafety.schema.SchemaInspector;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "flyway-safety-checker",
        mixinStandardHelpOptions = true,
        version = "1.0.0",
        description = "Analyses Flyway migration files for production safety risks."
)
public class Main implements Callable<Integer> {

    @Option(names = "--migrations-dir", required = true, description = "Path to Flyway migrations directory")
    private Path migrationsDir;

    @Option(names = "--jdbc-url", required = true, description = "JDBC URL for the baseline database")
    private String jdbcUrl;

    @Option(names = "--jdbc-user", defaultValue = "postgres", description = "Database username")
    private String jdbcUser;

    @Option(names = "--jdbc-password", defaultValue = "postgres", description = "Database password")
    private String jdbcPassword;

    @Option(names = "--base-branch", defaultValue = "origin/main", description = "Base branch for git diff")
    private String baseBranch;

    @Option(names = "--row-counts", description = "Path to pg_stat_user_tables CSV file")
    private Path rowCountsFile;

    @Option(names = "--output", description = "Output file for the Markdown report")
    private Path outputFile;

    @Option(names = "--skip-llm", description = "Skip LLM analysis (use rule-based only)")
    private boolean skipLlm;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        System.out.println("[flyway-safety-checker] Starting analysis...");

        // 1. Discover new migrations
        System.out.println("[flyway-safety-checker] Discovering new migrations vs " + baseBranch);
        MigrationDiscovery discovery = new MigrationDiscovery(migrationsDir);
        List<MigrationFile> newMigrations;
        try {
            newMigrations = discovery.discoverNewMigrations(baseBranch);
        } catch (IOException | InterruptedException e) {
            System.err.println("[flyway-safety-checker] Failed to discover migrations: " + e.getMessage());
            return 1;
        }

        if (newMigrations.isEmpty()) {
            System.out.println("[flyway-safety-checker] No new migration files found.");
            writeReport("No new migration files detected.", SafetyRating.GREEN);
            return 0;
        }

        System.out.println("[flyway-safety-checker] Found " + newMigrations.size() + " new migration(s): ");
        newMigrations.forEach(m -> System.out.println("  - " + m.filename()));

        // 2. Apply baseline migrations
        System.out.println("[flyway-safety-checker] Applying baseline migrations...");
        FlywayRunner flywayRunner = new FlywayRunner(jdbcUrl, jdbcUser, jdbcPassword);
        try {
            flywayRunner.runBaseline(migrationsDir, newMigrations);
        } catch (Exception e) {
            System.err.println("[flyway-safety-checker] Flyway baseline failed: " + e.getMessage());
            return 1;
        }

        // 3. Import row counts
        Map<String, Long> rowCounts = new RowCountImporter().importFrom(rowCountsFile);
        System.out.println("[flyway-safety-checker] Loaded row counts for " + rowCounts.size() + " table(s).");

        // 4. Inspect schema
        System.out.println("[flyway-safety-checker] Inspecting schema...");
        SchemaInspector inspector = new SchemaInspector(jdbcUrl, jdbcUser, jdbcPassword);
        SchemaContext schemaContext;
        try {
            schemaContext = inspector.inspect(rowCounts);
        } catch (Exception e) {
            System.err.println("[flyway-safety-checker] Schema inspection failed: " + e.getMessage());
            return 1;
        }

        // 5. Analyse each migration
        RuleBasedAnalyzer ruleAnalyzer = new RuleBasedAnalyzer();
        String llmApiKey = skipLlm ? null : System.getenv("ANTHROPIC_API_KEY");
        LLMAnalyzer llmAnalyzer = new LLMAnalyzer(llmApiKey);

        Map<MigrationFile, AnalysisResult> results = new LinkedHashMap<>();
        List<AnalysisResult> allResults = new ArrayList<>();

        for (MigrationFile migration : newMigrations) {
            System.out.println("[flyway-safety-checker] Analysing: " + migration.filename());

            AnalysisResult ruleResult = ruleAnalyzer.analyze(migration);
            System.out.println("  Rule-based: " + ruleResult.rating());

            AnalysisResult llmResult;
            if (skipLlm || llmApiKey == null || llmApiKey.isBlank()) {
                llmResult = AnalysisResult.of(SafetyRating.GREEN, List.of(), null);
            } else {
                llmResult = llmAnalyzer.analyze(migration, schemaContext);
                System.out.println("  LLM: " + llmResult.rating());
            }

            AnalysisResult merged = AnalysisResult.merge(ruleResult, llmResult);
            System.out.println("  Merged: " + merged.rating());

            results.put(migration, merged);
            allResults.add(merged);
        }

        // 6. Generate report
        SafetyRating overall = MarkdownReportGenerator.computeOverall(allResults);
        MarkdownReportGenerator generator = new MarkdownReportGenerator();
        String report = generator.generate(overall, results);

        System.out.println("\n[flyway-safety-checker] Overall rating: " + overall.emoji() + " " + overall);
        System.out.println("\n" + report);

        writeReport(report, overall);

        // Exit code 1 for RED so CI can detect dangerous migrations
        return overall == SafetyRating.RED ? 1 : 0;
    }

    private void writeReport(String content, SafetyRating rating) throws IOException {
        if (outputFile != null) {
            Files.writeString(outputFile, content);
            System.out.println("[flyway-safety-checker] Report written to " + outputFile);
        }
    }
}
