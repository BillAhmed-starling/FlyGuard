package com.flywaysafety.model;

public enum SafetyRating {
    GREEN, AMBER, RED;

    public static SafetyRating worst(SafetyRating a, SafetyRating b) {
        if (a == RED || b == RED) return RED;
        if (a == AMBER || b == AMBER) return AMBER;
        return GREEN;
    }

    public String emoji() {
        return switch (this) {
            case GREEN -> "🟢";
            case AMBER -> "🟡";
            case RED -> "🔴";
        };
    }

    public String badgeColor() {
        return switch (this) {
            case GREEN -> "brightgreen";
            case AMBER -> "yellow";
            case RED -> "red";
        };
    }
}
