package com.flywaysafety.model;

import java.nio.file.Path;

public record MigrationFile(
        String version,
        String filename,
        Path path,
        String sql
) implements Comparable<MigrationFile> {

    @Override
    public int compareTo(MigrationFile other) {
        return compareVersions(this.version, other.version);
    }

    private static int compareVersions(String v1, String v2) {
        if (v1 == null && v2 == null) return 0;
        if (v1 == null) return -1;
        if (v2 == null) return 1;
        String[] parts1 = v1.split("[._]");
        String[] parts2 = v2.split("[._]");
        int len = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < len; i++) {
            int n1 = i < parts1.length ? parseIntSafe(parts1[i]) : 0;
            int n2 = i < parts2.length ? parseIntSafe(parts2[i]) : 0;
            if (n1 != n2) return Integer.compare(n1, n2);
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
