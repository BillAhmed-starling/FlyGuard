package com.flywaysafety.schema;

import com.flywaysafety.model.SchemaContext;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SchemaInspector {

    private final String jdbcUrl;
    private final String username;
    private final String password;

    public SchemaInspector(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    public SchemaContext inspect(Map<String, Long> rowCounts) throws SQLException {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password)) {
            List<SchemaContext.TableInfo> tables = inspectTables(conn);
            List<SchemaContext.IndexInfo> indexes = inspectIndexes(conn);
            List<SchemaContext.ConstraintInfo> constraints = inspectConstraints(conn);
            return new SchemaContext(tables, indexes, constraints, rowCounts);
        }
    }

    private List<SchemaContext.TableInfo> inspectTables(Connection conn) throws SQLException {
        String sql = """
                SELECT c.table_name, c.column_name, c.data_type,
                       c.is_nullable, c.column_default
                FROM information_schema.columns c
                JOIN information_schema.tables t
                  ON c.table_name = t.table_name
                 AND c.table_schema = t.table_schema
                WHERE c.table_schema = 'public'
                  AND t.table_type = 'BASE TABLE'
                  AND c.table_name != 'flyway_schema_history'
                ORDER BY c.table_name, c.ordinal_position
                """;

        Map<String, List<SchemaContext.ColumnInfo>> tableMap = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String tableName = rs.getString("table_name");
                String columnName = rs.getString("column_name");
                String dataType = rs.getString("data_type");
                boolean nullable = "YES".equalsIgnoreCase(rs.getString("is_nullable"));
                String defaultVal = rs.getString("column_default");

                tableMap.computeIfAbsent(tableName, k -> new ArrayList<>())
                        .add(new SchemaContext.ColumnInfo(columnName, dataType, nullable, defaultVal));
            }
        }

        List<SchemaContext.TableInfo> tables = new ArrayList<>();
        for (Map.Entry<String, List<SchemaContext.ColumnInfo>> entry : tableMap.entrySet()) {
            tables.add(new SchemaContext.TableInfo(entry.getKey(), entry.getValue()));
        }
        return tables;
    }

    private List<SchemaContext.IndexInfo> inspectIndexes(Connection conn) throws SQLException {
        String sql = """
                SELECT schemaname, tablename, indexname, indexdef,
                       ix.indisunique AS is_unique
                FROM pg_indexes pi
                JOIN pg_class c ON c.relname = pi.indexname
                JOIN pg_index ix ON ix.indexrelid = c.oid
                WHERE pi.schemaname = 'public'
                  AND pi.tablename != 'flyway_schema_history'
                ORDER BY pi.tablename, pi.indexname
                """;

        List<SchemaContext.IndexInfo> indexes = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                indexes.add(new SchemaContext.IndexInfo(
                        rs.getString("tablename"),
                        rs.getString("indexname"),
                        rs.getString("indexdef"),
                        rs.getBoolean("is_unique")
                ));
            }
        }
        return indexes;
    }

    private List<SchemaContext.ConstraintInfo> inspectConstraints(Connection conn) throws SQLException {
        String sql = """
                SELECT tc.table_name, tc.constraint_name, tc.constraint_type,
                       cc.check_clause
                FROM information_schema.table_constraints tc
                LEFT JOIN information_schema.check_constraints cc
                  ON tc.constraint_name = cc.constraint_name
                 AND tc.constraint_schema = cc.constraint_schema
                WHERE tc.constraint_schema = 'public'
                  AND tc.table_name != 'flyway_schema_history'
                ORDER BY tc.table_name, tc.constraint_name
                """;

        List<SchemaContext.ConstraintInfo> constraints = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String definition = rs.getString("check_clause");
                if (definition == null) definition = "";
                constraints.add(new SchemaContext.ConstraintInfo(
                        rs.getString("table_name"),
                        rs.getString("constraint_name"),
                        rs.getString("constraint_type"),
                        definition
                ));
            }
        }
        return constraints;
    }
}
