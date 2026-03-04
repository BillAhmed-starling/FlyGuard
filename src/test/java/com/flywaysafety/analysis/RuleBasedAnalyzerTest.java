package com.flywaysafety.analysis;

import com.flywaysafety.model.MigrationFile;
import com.flywaysafety.model.SafetyRating;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuleBasedAnalyzerTest {

    private RuleBasedAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new RuleBasedAnalyzer();
    }

    // -----------------------------------------------------------------------
    // RED cases
    // -----------------------------------------------------------------------

    @Test
    void dropTable_isRed() {
        AnalysisResult result = analyze("DROP TABLE users;");
        assertEquals(SafetyRating.RED, result.rating());
        assertContains(result.findings(), "DROP TABLE");
    }

    @Test
    void dropColumn_isRed() {
        AnalysisResult result = analyze("ALTER TABLE users DROP COLUMN email;");
        assertEquals(SafetyRating.RED, result.rating());
        assertContains(result.findings(), "DROP COLUMN");
    }

    @Test
    void truncate_isRed() {
        AnalysisResult result = analyze("TRUNCATE TABLE orders;");
        assertEquals(SafetyRating.RED, result.rating());
        assertContains(result.findings(), "TRUNCATE");
    }

    @Test
    void deleteWithoutWhere_isRed() {
        AnalysisResult result = analyze("DELETE FROM sessions;");
        assertEquals(SafetyRating.RED, result.rating());
        assertContains(result.findings(), "DELETE without WHERE");
    }

    @Test
    void updateWithoutWhere_isRed() {
        AnalysisResult result = analyze("UPDATE accounts SET status = 'active';");
        assertEquals(SafetyRating.RED, result.rating());
        assertContains(result.findings(), "UPDATE without WHERE");
    }

    @Test
    void addColumnNotNullWithoutDefault_isRed() {
        AnalysisResult result = analyze("ALTER TABLE orders ADD COLUMN tenant_id BIGINT NOT NULL;");
        assertEquals(SafetyRating.RED, result.rating());
        assertContains(result.findings(), "NOT NULL without DEFAULT");
    }

    // -----------------------------------------------------------------------
    // AMBER cases
    // -----------------------------------------------------------------------

    @Test
    void createIndexWithoutConcurrently_isAmber() {
        AnalysisResult result = analyze("CREATE INDEX idx_orders_user ON orders(user_id);");
        assertEquals(SafetyRating.AMBER, result.rating());
        assertContains(result.findings(), "without CONCURRENTLY");
    }

    @Test
    void addColumnNotNullWithDefault_isAmber() {
        AnalysisResult result = analyze("ALTER TABLE orders ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'pending';");
        assertEquals(SafetyRating.AMBER, result.rating());
        assertContains(result.findings(), "NOT NULL WITH DEFAULT");
    }

    @Test
    void deleteWithWhere_isAmber() {
        AnalysisResult result = analyze("DELETE FROM sessions WHERE expires_at < NOW();");
        assertEquals(SafetyRating.AMBER, result.rating());
        assertContains(result.findings(), "DELETE with WHERE");
    }

    @Test
    void updateWithWhere_isAmber() {
        AnalysisResult result = analyze("UPDATE users SET email_verified = true WHERE id = 42;");
        assertEquals(SafetyRating.AMBER, result.rating());
        assertContains(result.findings(), "UPDATE with WHERE");
    }

    // -----------------------------------------------------------------------
    // GREEN cases
    // -----------------------------------------------------------------------

    @Test
    void createTable_isGreen() {
        AnalysisResult result = analyze("""
                CREATE TABLE audit_log (
                    id BIGSERIAL PRIMARY KEY,
                    action VARCHAR(100),
                    created_at TIMESTAMPTZ DEFAULT NOW()
                );
                """);
        assertEquals(SafetyRating.GREEN, result.rating());
        assertContains(result.findings(), "CREATE TABLE");
    }

    @Test
    void createIndexConcurrently_isGreen() {
        AnalysisResult result = analyze("CREATE INDEX CONCURRENTLY idx_users_email ON users(email);");
        assertEquals(SafetyRating.GREEN, result.rating());
        assertContains(result.findings(), "CONCURRENTLY");
    }

    @Test
    void addColumnNullable_isAmber() {
        // The ALTER itself is AMBER (AccessExclusiveLock), but adding a nullable column is a safe DDL within it
        AnalysisResult result = analyze("ALTER TABLE users ADD COLUMN nickname VARCHAR(100);");
        assertEquals(SafetyRating.AMBER, result.rating()); // AMBER from ALTER
        assertContains(result.findings(), "ADD COLUMN NULL");
    }

    // -----------------------------------------------------------------------
    // Multi-statement files
    // -----------------------------------------------------------------------

    @Test
    void multiStatementFile_worstRatingWins() {
        String sql = """
                CREATE TABLE audit_log (id BIGSERIAL PRIMARY KEY);
                DROP TABLE old_logs;
                """;
        AnalysisResult result = analyze(sql);
        assertEquals(SafetyRating.RED, result.rating(), "RED should dominate GREEN");
    }

    @Test
    void multiStatementFile_greenAndAmber_givesAmber() {
        String sql = """
                CREATE TABLE audit_log (id BIGSERIAL PRIMARY KEY);
                CREATE INDEX idx_audit ON audit_log(id);
                """;
        AnalysisResult result = analyze(sql);
        assertEquals(SafetyRating.AMBER, result.rating(), "AMBER should dominate GREEN");
    }

    // -----------------------------------------------------------------------
    // Parse failure
    // -----------------------------------------------------------------------

    @Test
    void parseFailure_isAmber() {
        AnalysisResult result = analyze("THIS IS NOT VALID SQL @@##!!;");
        assertEquals(SafetyRating.AMBER, result.rating());
        assertContains(result.findings(), "PARSE_FAILURE");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private AnalysisResult analyze(String sql) {
        MigrationFile migration = new MigrationFile("1", "V1__test.sql", Path.of("V1__test.sql"), sql);
        return analyzer.analyze(migration);
    }

    private void assertContains(List<String> findings, String keyword) {
        boolean found = findings.stream().anyMatch(f -> f.contains(keyword));
        assertTrue(found,
                "Expected a finding containing '" + keyword + "' but got: " + findings);
    }
}
