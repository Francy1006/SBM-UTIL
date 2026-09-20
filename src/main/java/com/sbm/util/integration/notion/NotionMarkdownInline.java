package com.sbm.util.integration.notion;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small inline parser shared by every text-bearing Markdown block. */
final class NotionMarkdownInline {
    private static final Map<String, String> STYLES = Map.of(
            "**", "bold", "__", "bold", "*", "italic", "_", "italic", "~~", "strikethrough");
    private static final List<String> MARKERS = List.of("**", "__", "~~", "*", "_");

    private NotionMarkdownInline() { }

    static List<Map<String, Object>> parse(String text) {
        return parse(text, Map.of(), null, 0);
    }

    static List<Map<String, Object>> literal(String text) {
        List<Map<String, Object>> result = new ArrayList<>();
        append(result, text, Map.of(), null);
        return result;
    }

    private static List<Map<String, Object>> parse(String text, Map<String, Boolean> styles,
                                                  String link, int depth) {
        List<Map<String, Object>> result = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        int index = 0;
        while (index < text.length()) {
            if (escaped(text, index)) {
                plain.append(text.charAt(index + 1));
                index += 2;
            } else {
                Match match = match(text, index, depth);
                if (match == null) {
                    plain.append(text.charAt(index++));
                } else {
                    append(result, plain.toString(), styles, link);
                    plain.setLength(0);
                    appendMatch(result, match, styles, link, depth);
                    index = match.end();
                }
            }
        }
        append(result, plain.toString(), styles, link);
        return result;
    }

    private static void appendMatch(List<Map<String, Object>> result, Match match,
                                    Map<String, Boolean> styles, String link, int depth) {
        Map<String, Boolean> nested = new LinkedHashMap<>(styles);
        if (match.style() != null) nested.put(match.style(), true);
        String target = match.link() == null ? link : match.link();
        if ("code".equals(match.style())) {
            append(result, match.content(), nested, target);
        } else {
            result.addAll(parse(match.content(), nested, target, depth + 1));
        }
    }

    private static Match match(String text, int start, int depth) {
        if (text.charAt(start) == '`') {
            int end = codeEnd(text, start);
            int width = runLength(text, start, '`');
            if (end >= 0) return new Match(text.substring(start + width, end), end + width, "code", null);
        }
        if (depth >= 32) return null;
        if (text.charAt(start) == '[') {
            Match link = link(text, start);
            if (link != null) return link;
        }
        return emphasis(text, start);
    }

    private static Match emphasis(String text, int start) {
        for (String marker : MARKERS) {
            if (!text.startsWith(marker, start) || !canOpen(text, start, marker)) continue;
            int end = closing(text, start + marker.length(), marker);
            if (end > start + marker.length()) {
                return new Match(text.substring(start + marker.length(), end),
                        end + marker.length(), STYLES.get(marker), null);
            }
        }
        return null;
    }

    private static boolean canOpen(String text, int start, String marker) {
        int next = start + marker.length();
        if (next >= text.length() || Character.isWhitespace(text.charAt(next))) return false;
        return marker.charAt(0) != '_' || start == 0 || !Character.isLetterOrDigit(text.charAt(start - 1));
    }

    private static int closing(String text, int start, String marker) {
        int index = start;
        while (index < text.length()) {
            if (escaped(text, index)) {
                index += 2;
            } else if (text.charAt(index) == '`') {
                int end = codeEnd(text, index);
                if (end >= 0) {
                    index = end + runLength(text, index, '`');
                } else {
                    index++;
                }
            } else if (text.startsWith(marker, index) && !Character.isWhitespace(text.charAt(index - 1))
                    && canClose(text, index, marker)) {
                return index;
            } else {
                index++;
            }
        }
        return -1;
    }

    private static boolean canClose(String text, int index, String marker) {
        int next = index + marker.length();
        return marker.charAt(0) != '_' || next == text.length() || !Character.isLetterOrDigit(text.charAt(next));
    }

    static int runLength(String text, int start, char delimiter) {
        int end = start;
        while (end < text.length() && text.charAt(end) == delimiter) end++;
        return end - start;
    }

    static int codeEnd(String text, int start) {
        int width = runLength(text, start, '`');
        int index = start + width;
        while (index < text.length()) {
            if (text.charAt(index) != '`') {
                index++;
                continue;
            }
            int length = runLength(text, index, '`');
            if (length == width) return index;
            index += length;
        }
        return -1;
    }

    private static Match link(String text, int start) {
        int labelEnd = text.indexOf("](", start + 1);
        if (labelEnd < 0) return null;
        int end = linkEnd(text, labelEnd + 2);
        if (end < 0) return null;
        String url = text.substring(labelEnd + 2, end);
        if (!validUrl(url)) return null;
        return new Match(text.substring(start + 1, labelEnd), end + 1, null, url);
    }

    private static int linkEnd(String text, int start) {
        int nesting = 0;
        int index = start;
        while (index < text.length()) {
            char value = text.charAt(index);
            if (value == '(') nesting++;
            if (value == ')' && nesting-- == 0) return index;
            index++;
        }
        return -1;
    }

    private static boolean validUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if ("mailto".equalsIgnoreCase(scheme)) return !uri.getSchemeSpecificPart().isBlank();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean escaped(String text, int index) {
        return text.charAt(index) == '\\' && index + 1 < text.length()
                && "\\`*_{}[]()#+-.!|>~".indexOf(text.charAt(index + 1)) >= 0;
    }

    private static void append(List<Map<String, Object>> result, String text,
                               Map<String, Boolean> styles, String link) {
        int cursor = 0;
        while (cursor < text.length()) {
            int end = Math.min(cursor + 2000, text.length());
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("content", text.substring(cursor, end));
            if (link != null) content.put("link", Map.of("url", link));
            Map<String, Object> segment = new LinkedHashMap<>();
            segment.put("type", "text");
            segment.put("text", content);
            if (!styles.isEmpty()) segment.put("annotations", new LinkedHashMap<>(styles));
            result.add(segment);
            cursor = end;
        }
    }

    private record Match(String content, int end, String style, String link) { }
}
