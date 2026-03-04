# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Compile
mvn compile -f pom.xml

# Run all tests (unit + integration via Testcontainers — requires Docker)
mvn test -f pom.xml

# Run a single test class
mvn test -Dtest=RuleBasedAnalyzerTest -f pom.xml

# Build shaded fat JAR (skipping tests)
mvn package -DskipTests -f pom.xml
# Output: target/flyway-safety-checker-1.0.0.jar

# Local smoke run (rule-based only, no Docker needed)
java -jar target/flyway-safety-checker-1.0.0.jar \
  --migrations-dir ./db/migrations \
  --jdbc-url jdbc:postgresql://localhost:5432/safety_check \
  --base-branch origin/main \
  --skip-llm \
  --output report.md

# With LLM (requires ANTHROPIC_API_KEY env var)
ANTHROPIC_API_KEY=sk-... java -jar target/flyway-safety-checker-1.0.0.jar \
  --migrations-dir ./db/migrations \
  --jdbc-url jdbc:postgresql://localhost:5432/safety_check \
  --base-branch origin/main \
  --row-counts pg_stat_user_tables.csv \
  --output report.md
```

**Critical build note:** The fat JAR requires `maven-shade-plugin` with **`ServicesResourceTransformer`** — without it, Flyway 10.x SPI discovery fails at runtime. This is already configured in `pom.xml`.

## Architecture

The tool is a strictly sequential pipeline that runs in CI on every PR touching `db/migrations/**.sql`:

```
git diff  →  MigrationDiscovery
           →  FlywayRunner (applies baseline, excluding new PR migrations)
           →  SchemaInspector (pg_catalog) + RowCountImporter (CSV)
           →  per-migration:
                RuleBasedAnalyzer (JSqlParser AST)
                LLMAnalyzer       (Claude claude-opus-4-6)
                merge → worst rating wins
           →  MarkdownReportGenerator → PR comment + exit code
```

**Exit code:** `1` for RED, `0` otherwise. GitHub Actions uses `continue-on-error: true` so the comment is always posted even when RED blocks the CI check.

### Rating System

`SafetyRating` is `GREEN < AMBER < RED`. `SafetyRating.worst(a, b)` is used everywhere — at the statement level within a file, across rule vs. LLM results (`AnalysisResult.merge()`), and across all migrations for the overall report rating.

### Baseline Isolation Strategy

`FlywayRunner` discovers all migrations in the directory, identifies those **not** in `newMigrations`, finds the highest version among those, and passes `.target(highestExistingVersion)` to Flyway. This applies the existing schema without running untested PR code, enabling accurate schema inspection.

### Dual Analysis

Each migration is assessed independently by two analyzers whose results are merged:

- **`RuleBasedAnalyzer`** — splits on `;` then calls `CCJSqlParserUtil.parse()` per statement, giving access to both the AST and the raw SQL string. Maps SQL AST node types to ratings. Parse failures → AMBER with `PARSE_FAILURE` finding. Never returns GREEN for an ALTER TABLE (all ALTERs are at least AMBER due to AccessExclusiveLock). **Important:** JSqlParser 4.9 does not support the `CONCURRENTLY` keyword and throws a `ParseException` on `CREATE INDEX CONCURRENTLY`. This is handled by matching the raw SQL string with `(?s)CREATE\s+INDEX\s+CONCURRENTLY.*` *before* calling the parser — that branch returns GREEN directly and skips JSqlParser entirely.

- **`LLMAnalyzer`** — calls `claude-opus-4-6` via OkHttp with a 120s read timeout. User message includes the raw SQL and `SchemaContext.toContextString()` (tables, columns, indexes, constraints, row counts). Expects strict JSON `{"rating", "risks", "explanation"}` with markdown fence stripping. Any failure → AMBER (never silently GREEN).

### Key Data Flow Types

- `MigrationFile` (record): version string, filename, path, raw SQL content
- `SchemaContext` (nested records): tables→columns, indexes, constraints, `Map<String, Long>` row counts
- `AnalysisResult` (record): rating, List<String> findings, LLM explanation string
- `RowCountImporter` reads a CSV of `pg_stat_user_tables` columns; only `relname` and `n_live_tup` are consumed; absent file → empty map (row counts are optional context)

### MigrationDiscovery

Uses `git diff <baseBranch> --name-only --diff-filter=A` (added files only). Matches filenames against `^(V([0-9._]+)|R)__.*\.sql$`. The GitHub Actions workflow checks out with `fetch-depth: 0` — this is required for git diff to see the base branch.

### GitHub Actions Workflow

`.github/workflows/migration-safety.yml` triggers on `pull_request` for SQL paths, spins up a `postgres:16` service container, builds the JAR, runs the checker, then uses `actions/github-script@v7` to upsert a PR comment (identified by a `<!-- flyway-safety-checker -->` marker). Report is also uploaded as a workflow artifact with 30-day retention.

The `pg_stat_user_tables.csv` file (row counts) is expected at `.github/pg_stat_user_tables.csv` if present. Generate it from a read replica:
```sql
\COPY pg_stat_user_tables TO 'pg_stat_user_tables.csv' CSV HEADER
```

## Package Summary

| Package | Responsibility |
|---|---|
| `com.flywaysafety` | `Main.java` — picocli CLI, pipeline orchestration |
| `migration/` | `MigrationDiscovery` (git diff), `FlywayRunner` (baseline) |
| `schema/` | `SchemaInspector` (pg_catalog queries), `RowCountImporter` (CSV) |
| `analysis/` | `RuleBasedAnalyzer`, `LLMAnalyzer`, `AnalysisResult` |
| `model/` | `SafetyRating`, `MigrationFile`, `SchemaContext` |
| `report/` | `MarkdownReportGenerator` (shields.io badges + Markdown) |
