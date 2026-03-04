package com.flywaysafety.schema;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads a CSV export of pg_stat_user_tables and returns a map of
 * table name -> approximate live row count (n_live_tup).
 *
 * The CSV must have a header row. Only "relname" and "n_live_tup" columns
 * are consumed; all other columns are ignored.
 *
 * Returns an empty map if the path is null or the file does not exist.
 */
public class RowCountImporter {

    public Map<String, Long> importFrom(Path csvPath) {
        Map<String, Long> result = new HashMap<>();
        if (csvPath == null || !Files.exists(csvPath)) {
            return result;
        }

        try (BufferedReader reader = Files.newBufferedReader(csvPath)) {
            String headerLine = reader.readLine();
            if (headerLine == null) return result;

            String[] headers = parseCsvLine(headerLine);
            int relNameIdx = indexOf(headers, "relname");
            int nLiveTupIdx = indexOf(headers, "n_live_tup");

            if (relNameIdx < 0 || nLiveTupIdx < 0) {
                System.err.println("[RowCountImporter] CSV missing required columns 'relname' or 'n_live_tup'. Found: " + Arrays.toString(headers));
                return result;
            }

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] cols = parseCsvLine(line);
                if (cols.length <= Math.max(relNameIdx, nLiveTupIdx)) continue;
                String tableName = cols[relNameIdx].trim();
                String rawCount = cols[nLiveTupIdx].trim();
                try {
                    long count = Long.parseLong(rawCount);
                    result.put(tableName, count);
                } catch (NumberFormatException e) {
                    // skip rows with non-numeric counts
                }
            }
        } catch (IOException e) {
            System.err.println("[RowCountImporter] Failed to read CSV: " + e.getMessage());
        }

        return result;
    }

    private int indexOf(String[] headers, String name) {
        for (int i = 0; i < headers.length; i++) {
            if (headers[i].trim().equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    /**
     * Minimal CSV line parser that handles quoted fields.
     */
    private String[] parseCsvLine(String line) {
        java.util.List<String> fields = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }
}
