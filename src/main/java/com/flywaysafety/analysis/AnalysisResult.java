package com.flywaysafety.analysis;

import com.flywaysafety.model.SafetyRating;

import java.util.ArrayList;
import java.util.List;

public record AnalysisResult(
        SafetyRating rating,
        List<String> findings,
        String llmExplanation
) {

    public static AnalysisResult of(SafetyRating rating, List<String> findings, String llmExplanation) {
        return new AnalysisResult(rating, findings, llmExplanation);
    }

    public static AnalysisResult merge(AnalysisResult ruleResult, AnalysisResult llmResult) {
        SafetyRating worst = SafetyRating.worst(ruleResult.rating(), llmResult.rating());
        List<String> allFindings = new ArrayList<>();
        allFindings.addAll(ruleResult.findings());
        allFindings.addAll(llmResult.findings());
        String explanation = llmResult.llmExplanation();
        return new AnalysisResult(worst, allFindings, explanation);
    }
}
