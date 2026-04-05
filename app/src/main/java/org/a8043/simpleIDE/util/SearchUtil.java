package org.a8043.simpleIDE.util;

import lombok.AllArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

public class SearchUtil {
    public static <T> List<T> search(List<T> list, Function<T, String> toStringFunc, String keyword) {
        if (list == null || keyword == null || keyword.trim().isEmpty()) {
            return list != null ? new ArrayList<>(list) : new ArrayList<>();
        }

        keyword = keyword.toLowerCase().trim();
        String keywordWithoutSpaces = keyword.replaceAll("\\s+", "");

        List<SearchResult<T>> results = new ArrayList<>();
        for (T item : list) {
            if (item == null) {
                continue;
            }

            String str = toStringFunc.apply(item);
            String lowerStr = str.toLowerCase();
            int score = calculateMatchScore(str, lowerStr, keyword, keywordWithoutSpaces);

            if (score > 0) {
                results.add(new SearchResult<>(item, score));
            }
        }

        results.sort((a, b) -> b.score - a.score);
        return results.stream()
            .map(r -> r.value)
            .collect(Collectors.toList());
    }

    private static int calculateMatchScore(String original, String lowerCase,
                                           String keyword, String keywordWithoutSpaces) {
        int score = 0;

        if (original.equalsIgnoreCase(keyword)) {
            score += 1000;
        }

        if (lowerCase.contains(keyword)) {
            score += 500;
        }

        if (matchesAbbreviation(original, keywordWithoutSpaces)) {
            score += 400;
        }

        if (matchesCamelCase(original, keywordWithoutSpaces)) {
            score += 300;
        }

        int partialMatches = countPartialMatches(lowerCase, keyword);
        score += partialMatches * 5;

        if (lowerCase.startsWith(keyword)) {
            score += 50;
        }

        if (matchesWordBoundary(original, keyword)) {
            score += 100;
        }

        return score;
    }

    private static boolean matchesAbbreviation(String str, String abbreviation) {
        if (abbreviation.isEmpty()) {
            return false;
        }

        String[] words = str.split("(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])");
        StringBuilder initials = new StringBuilder();

        for (String word : words) {
            if (!word.isEmpty()) {
                initials.append(word.charAt(0));
            }
        }

        return initials.toString().toLowerCase().startsWith(abbreviation.toLowerCase());
    }

    private static boolean matchesCamelCase(String str, String pattern) {
        if (pattern.isEmpty()) {
            return false;
        }

        StringBuilder camelChars = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (Character.isUpperCase(c) || (i == 0 && Character.isLetter(c))) {
                camelChars.append(Character.toLowerCase(c));
            }
        }

        String camelPattern = camelChars.toString();
        return camelPattern.contains(pattern.toLowerCase());
    }

    private static int countPartialMatches(String str, String keyword) {
        int matches = 0;
        int strIndex = 0;
        int keywordIndex = 0;

        while (strIndex < str.length() && keywordIndex < keyword.length()) {
            if (str.charAt(strIndex) == keyword.charAt(keywordIndex)) {
                matches++;
                keywordIndex++;
            }
            strIndex++;
        }

        return matches;
    }

    private static boolean matchesWordBoundary(String str, String keyword) {
        for (String word : str.split("(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])|\\s+")) {
            if (word.toLowerCase().startsWith(keyword.toLowerCase())) {
                return true;
            }
        }

        return false;
    }

    @AllArgsConstructor
    private static class SearchResult<T> {
        private final T value;
        private final int score;
    }
}
