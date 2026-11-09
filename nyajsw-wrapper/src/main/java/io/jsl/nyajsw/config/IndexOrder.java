package io.jsl.nyajsw.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Orders the indices of {@code <n>} key families as YAJSW's reference describes ("number or string,
 * sorted alphanumerically"): runs of digits compare by value and come before other text, which
 * compares character by character. So {@code 1 < 5 < ab5 < ab12}, {@code 2 < 2.1 < 2.10 < 3}, and
 * {@code 7} equals {@code 07}.
 */
final class IndexOrder {

    /** The order; indices that differ only in leading zeros of a digit run are equal. */
    static final Comparator<String> NATURAL = IndexOrder::compare;

    private IndexOrder() {
    }

    /**
     * The index with leading zeros removed from every run of digits ({@code 07} gives {@code 7},
     * {@code ab007.01} gives {@code ab7.1}); two indices are the same exactly when these are equal.
     */
    static String normalise(String index) {
        StringBuilder out = new StringBuilder(index.length());
        for (String run : runs(index)) {
            out.append(isDigits(run) ? stripLeadingZeros(run) : run);
        }
        return out.toString();
    }

    private static int compare(String first, String second) {
        List<String> a = runs(first);
        List<String> b = runs(second);
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int order = compareRuns(a.get(i), b.get(i));
            if (order != 0) {
                return order;
            }
        }
        return Integer.compare(a.size(), b.size());
    }

    private static int compareRuns(String a, String b) {
        boolean digitsA = isDigits(a);
        boolean digitsB = isDigits(b);
        if (digitsA != digitsB) {
            return digitsA ? -1 : 1;
        }
        if (!digitsA) {
            return a.compareTo(b);
        }
        // By value without parsing, so an index of any length works.
        String valueA = stripLeadingZeros(a);
        String valueB = stripLeadingZeros(b);
        int byLength = Integer.compare(valueA.length(), valueB.length());
        return byLength != 0 ? byLength : valueA.compareTo(valueB);
    }

    /** Splits into maximal runs of ASCII digits and of other characters. */
    private static List<String> runs(String text) {
        List<String> runs = new ArrayList<>();
        int start = 0;
        for (int i = 1; i <= text.length(); i++) {
            if (i == text.length() || isDigit(text.charAt(i)) != isDigit(text.charAt(start))) {
                runs.add(text.substring(start, i));
                start = i;
            }
        }
        return runs;
    }

    private static boolean isDigits(String run) {
        return !run.isEmpty() && isDigit(run.charAt(0));
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static String stripLeadingZeros(String digits) {
        int i = 0;
        while (i < digits.length() - 1 && digits.charAt(i) == '0') {
            i++;
        }
        return digits.substring(i);
    }
}
