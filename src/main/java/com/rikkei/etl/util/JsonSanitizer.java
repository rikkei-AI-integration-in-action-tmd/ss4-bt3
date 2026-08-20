package com.rikkei.etl.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JsonSanitizer {

    private static final Pattern MARKDOWN_JSON_PATTERN = Pattern.compile(
            "```(?:json)?\\s*([\\s\\S]*?)\\s*```",
            Pattern.CASE_INSENSITIVE
    );

    private JsonSanitizer() {}

    public static String clean(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            throw new IllegalArgumentException("Raw AI content cannot be empty");
        }

        String cleaned = rawContent.trim();
        Matcher matcher = MARKDOWN_JSON_PATTERN.matcher(cleaned);
        if (matcher.find()) {
            cleaned = matcher.group(1).trim();
        }

        int firstBrace = cleaned.indexOf('{');
        int lastBrace = cleaned.lastIndexOf('}');

        if (firstBrace != -1 && lastBrace != -1 && lastBrace >= firstBrace) {
            cleaned = cleaned.substring(firstBrace, lastBrace + 1).trim();
        }

        return cleaned;
    }
}
