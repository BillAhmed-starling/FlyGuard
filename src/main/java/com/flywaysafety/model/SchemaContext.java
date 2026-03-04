package com.flywaysafety.model;

import java.util.List;
import java.util.Map;

public record SchemaContext(
        List<TableInfo> tables,
        List<IndexInfo> indexes,
        List<ConstraintInfo> constraints,
        Map<String, Long> rowCounts
) {

    public record TableInfo(
            String tableName,
            List<ColumnInfo> columns
    ) {}

    public record ColumnInfo(
            String columnName,
            String dataType,
            boolean nullable,
            String columnDefault
    ) {}

    public record IndexInfo(
            String tableName,
            String indexName,
            String indexDef,
            boolean unique
    ) {}

    public record ConstraintInfo(
            String tableName,
            String constraintName,
            String constraintType,
            String definition
    ) {}

    /**
     * Produces a compact text description of the schema for LLM context,
     * including row counts for any tables that appear in the provided SQL.
     */
    public String toContextString(String sql) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Current Schema ===\n\n");

        for (TableInfo table : tables) {
            long rows = rowCounts.getOrDefault(table.tableName(), -1L);
            sb.append("TABLE ").append(table.tableName());
            if (rows >= 0) {
                sb.append(" (~").append(rows).append(" rows)");
            }
            sb.append(":\n");
            for (ColumnInfo col : table.columns()) {
                sb.append("  ").append(col.columnName())
                  .append(" ").append(col.dataType())
                  .append(col.nullable() ? " NULL" : " NOT NULL");
                if (col.columnDefault() != null) {
                    sb.append(" DEFAULT ").append(col.columnDefault());
                }
                sb.append("\n");
            }
        }

        if (!indexes.isEmpty()) {
            sb.append("\nINDEXES:\n");
            for (IndexInfo idx : indexes) {
                sb.append("  ").append(idx.indexDef()).append("\n");
            }
        }

        if (!constraints.isEmpty()) {
            sb.append("\nCONSTRAINTS:\n");
            for (ConstraintInfo c : constraints) {
                sb.append("  ").append(c.tableName()).append(".")
                  .append(c.constraintName())
                  .append(" (").append(c.constraintType()).append(")")
                  .append(": ").append(c.definition()).append("\n");
            }
        }

        return sb.toString();
    }
}
