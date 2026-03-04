package com.flywaysafety.analysis;

import com.flywaysafety.model.MigrationFile;
import com.flywaysafety.model.SafetyRating;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.create.index.CreateIndex;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.update.Update;

import java.util.ArrayList;
import java.util.List;

public class RuleBasedAnalyzer {

    public AnalysisResult analyze(MigrationFile migration) {
        String sql = migration.sql();
        List<String> findings = new ArrayList<>();
        SafetyRating worstRating = SafetyRating.GREEN;

        // Split on semicolons to get raw per-statement text.
        // We parse each individually so we have both the AST and the raw string
        // (needed for keywords JSqlParser may drop from its AST, e.g. CONCURRENTLY).
        String[] rawStatements = sql.split(";");
        boolean parsedAny = false;

        for (String rawStmt : rawStatements) {
            String trimmed = rawStmt.strip();
            if (trimmed.isEmpty()) continue;

            // JSqlParser does not support the CONCURRENTLY keyword — detect before parsing.
            if (trimmed.toUpperCase().matches("(?s)CREATE\\s+INDEX\\s+CONCURRENTLY.*")) {
                findings.add("GREEN: CREATE INDEX CONCURRENTLY — non-blocking");
                parsedAny = true;
                continue;
            }

            Statement stmt;
            try {
                stmt = CCJSqlParserUtil.parse(trimmed);
            } catch (Exception e) {
                findings.add("PARSE_FAILURE: Could not parse statement — " + e.getMessage().split("\n")[0]);
                worstRating = SafetyRating.worst(worstRating, SafetyRating.AMBER);
                continue;
            }

            parsedAny = true;
            AnalysisResult stmtResult = analyzeStatement(stmt);
            worstRating = SafetyRating.worst(worstRating, stmtResult.rating());
            findings.addAll(stmtResult.findings());
        }

        if (!parsedAny && findings.isEmpty()) {
            findings.add("GREEN: No statements detected");
        }

        return AnalysisResult.of(worstRating, findings, null);
    }

    private AnalysisResult analyzeStatement(Statement stmt) {
        List<String> findings = new ArrayList<>();
        SafetyRating rating = SafetyRating.GREEN;

        if (stmt instanceof Drop drop) {
            String type = drop.getType().toUpperCase();
            if ("TABLE".equals(type)) {
                findings.add("RED: DROP TABLE detected — irreversible data loss");
                rating = SafetyRating.RED;
            } else if ("INDEX".equals(type)) {
                findings.add("AMBER: DROP INDEX acquires AccessExclusiveLock");
                rating = SafetyRating.AMBER;
            } else {
                findings.add("AMBER: DROP " + type + " detected");
                rating = SafetyRating.AMBER;
            }

        } else if (stmt instanceof Truncate) {
            findings.add("RED: TRUNCATE TABLE detected — irreversible data loss");
            rating = SafetyRating.RED;

        } else if (stmt instanceof Delete delete) {
            if (delete.getWhere() == null) {
                findings.add("RED: DELETE without WHERE — will remove all rows");
                rating = SafetyRating.RED;
            } else {
                findings.add("AMBER: DELETE with WHERE — scoped but requires review");
                rating = SafetyRating.AMBER;
            }

        } else if (stmt instanceof Update update) {
            if (update.getWhere() == null) {
                findings.add("RED: UPDATE without WHERE — will update all rows");
                rating = SafetyRating.RED;
            } else {
                findings.add("AMBER: UPDATE with WHERE — scoped but requires review");
                rating = SafetyRating.AMBER;
            }

        } else if (stmt instanceof Alter alter) {
            // Process each alter expression
            SafetyRating alterRating = SafetyRating.AMBER; // any ALTER is at least AMBER
            findings.add("AMBER: ALTER TABLE acquires AccessExclusiveLock");

            for (AlterExpression expr : alter.getAlterExpressions()) {
                AnalysisResult exprResult = analyzeAlterExpression(expr);
                alterRating = SafetyRating.worst(alterRating, exprResult.rating());
                findings.addAll(exprResult.findings());
            }
            rating = alterRating;

        } else if (stmt instanceof CreateIndex) {
            // Note: CREATE INDEX CONCURRENTLY is intercepted before reaching this branch
            // (JSqlParser cannot parse CONCURRENTLY). Any CreateIndex here lacks CONCURRENTLY.
            findings.add("AMBER: CREATE INDEX without CONCURRENTLY — acquires ShareLock, blocks writes");
            rating = SafetyRating.AMBER;

        } else if (stmt instanceof CreateTable) {
            findings.add("GREEN: CREATE TABLE — no impact on existing data");
            rating = SafetyRating.GREEN;

        } else {
            // Unknown statements default to AMBER — treat as advisory
            String stmtClass = stmt.getClass().getSimpleName();
            findings.add("AMBER: " + stmtClass + " — manual review recommended");
            rating = SafetyRating.AMBER;
        }

        return AnalysisResult.of(rating, findings, null);
    }

    private AnalysisResult analyzeAlterExpression(AlterExpression expr) {
        List<String> findings = new ArrayList<>();
        SafetyRating rating = SafetyRating.GREEN; // will be merged with parent AMBER

        String operation = expr.getOperation() != null ? expr.getOperation().name().toUpperCase() : "";

        if ("DROP".equals(operation)) {
            if (expr.getColumnName() != null) {
                findings.add("RED: DROP COLUMN — may cause data loss and locks table");
                rating = SafetyRating.RED;
            }
        } else if ("ADD".equals(operation)) {
            List<AlterExpression.ColumnDataType> columns = expr.getColDataTypeList();
            if (columns != null) {
                for (AlterExpression.ColumnDataType col : columns) {
                    AnalysisResult colResult = analyzeAddColumn(col);
                    rating = SafetyRating.worst(rating, colResult.rating());
                    findings.addAll(colResult.findings());
                }
            }
        }

        return AnalysisResult.of(rating, findings, null);
    }

    private AnalysisResult analyzeAddColumn(AlterExpression.ColumnDataType col) {
        List<String> findings = new ArrayList<>();

        String colSpec = col.toString().toUpperCase();
        boolean hasNotNull = colSpec.contains("NOT NULL");
        boolean hasDefault = colSpec.contains("DEFAULT");

        if (hasNotNull && !hasDefault) {
            findings.add("RED: ADD COLUMN NOT NULL without DEFAULT — requires full table rewrite and lock");
            return AnalysisResult.of(SafetyRating.RED, findings, null);
        } else if (hasNotNull) {
            findings.add("AMBER: ADD COLUMN NOT NULL WITH DEFAULT — may rewrite table on older PostgreSQL (<11)");
            return AnalysisResult.of(SafetyRating.AMBER, findings, null);
        } else {
            findings.add("GREEN: ADD COLUMN NULL — safe online DDL");
            return AnalysisResult.of(SafetyRating.GREEN, findings, null);
        }
    }

}
