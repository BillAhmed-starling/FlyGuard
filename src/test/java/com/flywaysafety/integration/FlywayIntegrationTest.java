package com.flywaysafety.integration;

import com.flywaysafety.analysis.AnalysisResult;
import com.flywaysafety.analysis.RuleBasedAnalyzer;
import com.flywaysafety.model.MigrationFile;
import com.flywaysafety.model.SafetyRating;
import com.flywaysafety.model.SchemaContext;
import com.flywaysafety.schema.SchemaInspector;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class FlywayIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("safety_check")
            .withUsername("postgres")
            .withPassword("postgres");

    private Path migrationsDir;
    private String jdbcUrl;

    @BeforeEach
    void setUp() throws IOException {
        jdbcUrl = postgres.getJdbcUrl();
        migrationsDir = Files.createTempDirectory("flyway-test-migrations");

        // Seed baseline migrations
        Files.writeString(migrationsDir.resolve("V1__create_users.sql"), """
                CREATE TABLE users (
                    id BIGSERIAL PRIMARY KEY,
                    email VARCHAR(255) NOT NULL UNIQUE,
                    created_at TIMESTAMPTZ DEFAULT NOW()
                );
                """);

        Files.writeString(migrationsDir.resolve("V2__create_orders.sql"), """
                CREATE TABLE orders (
                    id BIGSERIAL PRIMARY KEY,
                    user_id BIGINT NOT NULL REFERENCES users(id),
                    total DECIMAL(10,2) NOT NULL,
                    status VARCHAR(50) DEFAULT 'pending'
                );
                CREATE INDEX idx_orders_user ON orders(user_id);
                """);
    }

    @Test
    void baseline_appliesSuccessfully() throws Exception {
        runFlyway("2");

        try (Connection conn = DriverManager.getConnection(jdbcUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'users' AND table_schema = 'public'");
            rs.next();
            assertEquals(1, rs.getInt(1), "users table should exist after baseline");
        }
    }

    @Test
    void schemaInspector_returnsTablesAndColumns() throws Exception {
        runFlyway("2");

        SchemaInspector inspector = new SchemaInspector(jdbcUrl, "postgres", "postgres");
        SchemaContext ctx = inspector.inspect(Map.of());

        assertTrue(ctx.tables().stream().anyMatch(t -> t.tableName().equals("users")),
                "users table should be inspected");
        assertTrue(ctx.tables().stream().anyMatch(t -> t.tableName().equals("orders")),
                "orders table should be inspected");

        SchemaContext.TableInfo usersTable = ctx.tables().stream()
                .filter(t -> t.tableName().equals("users"))
                .findFirst()
                .orElseThrow();

        assertTrue(usersTable.columns().stream().anyMatch(c -> c.columnName().equals("email")),
                "email column should be present");
    }

    @Test
    void schemaInspector_excludesFlywaySchemHistory() throws Exception {
        runFlyway("2");

        SchemaInspector inspector = new SchemaInspector(jdbcUrl, "postgres", "postgres");
        SchemaContext ctx = inspector.inspect(Map.of());

        boolean hasFlywayTable = ctx.tables().stream()
                .anyMatch(t -> t.tableName().equals("flyway_schema_history"));
        assertFalse(hasFlywayTable, "flyway_schema_history should be excluded from schema context");
    }

    @Test
    void ruleBasedAnalyzer_greenMigration_ratedGreen() {
        String sql = "CREATE TABLE audit_log (id BIGSERIAL PRIMARY KEY, message TEXT);";
        MigrationFile migration = new MigrationFile("3", "V3__audit_log.sql", Path.of("V3__audit_log.sql"), sql);

        RuleBasedAnalyzer analyzer = new RuleBasedAnalyzer();
        AnalysisResult result = analyzer.analyze(migration);

        assertEquals(SafetyRating.GREEN, result.rating(),
                "CREATE TABLE should be rated GREEN");
    }

    @Test
    void ruleBasedAnalyzer_dropTable_ratedRed() {
        String sql = "DROP TABLE users;";
        MigrationFile migration = new MigrationFile("3", "V3__drop_users.sql", Path.of("V3__drop_users.sql"), sql);

        RuleBasedAnalyzer analyzer = new RuleBasedAnalyzer();
        AnalysisResult result = analyzer.analyze(migration);

        assertEquals(SafetyRating.RED, result.rating(),
                "DROP TABLE should be rated RED");
    }

    @Test
    void schemaContext_toContextString_includesRowCounts() throws Exception {
        runFlyway("2");

        Map<String, Long> rowCounts = Map.of("users", 50_000L, "orders", 250_000L);
        SchemaInspector inspector = new SchemaInspector(jdbcUrl, "postgres", "postgres");
        SchemaContext ctx = inspector.inspect(rowCounts);

        String contextStr = ctx.toContextString("SELECT * FROM users");
        assertTrue(contextStr.contains("50000"), "Row count for users should appear in context");
    }

    private void runFlyway(String target) {
        Flyway.configure()
                .dataSource(jdbcUrl, "postgres", "postgres")
                .locations("filesystem:" + migrationsDir.toAbsolutePath())
                .target(target)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }
}
