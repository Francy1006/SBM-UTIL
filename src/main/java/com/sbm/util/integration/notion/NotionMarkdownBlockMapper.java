package com.sbm.util.integration.notion;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Service
public class NotionMarkdownBlockMapper {
    private static final String RICH_TEXT = "rich_text";
    private static final String PLAIN_TEXT = "plain text";
    private static final Set<String> LANGUAGES = Set.of(
            "abap", "arduino", "bash", "basic", "c", "clojure", "coffeescript", "c++", "c#", "css",
            "dart", "diff", "docker", "elixir", "elm", "erlang", "flow", "fortran", "f#", "gherkin",
            "glsl", "go", "graphql", "groovy", "haskell", "html", "java", "java/c/c++/c#", "javascript", "json",
            "julia", "kotlin", "latex", "less", "lisp", "livescript", "lua", "makefile", "markdown",
            "markup", "matlab", "mermaid", "nix", "objective-c", "ocaml", "pascal", "perl", "php",
            PLAIN_TEXT, "powershell", "prolog", "protobuf", "python", "r", "reason", "ruby", "rust",
            "sass", "scala", "scheme", "scss", "shell", "sql", "swift", "typescript", "vb.net",
            "verilog", "vhdl", "visual basic", "webassembly", "xml", "yaml");
    private static final Map<String, String> LANGUAGE_ALIASES = Map.of(
            "js", "javascript", "ts", "typescript", "py", "python", "sh", "shell",
            "yml", "yaml", "md", "markdown", "dockerfile", "docker", "cs", "c#", "cpp", "c++");

    private final tools.jackson.databind.json.JsonMapper json = new tools.jackson.databind.json.JsonMapper();

    public String toChildrenJson(String markdown) {
        // Keep line endings for exact code-fence content; strip them only for block recognition.
        List<String> raw = splitLines(markdown);
        List<String> lines = raw.stream().map(NotionMarkdownBlockMapper::withoutEnding).toList();
        List<Map<String, Object>> blocks = new ArrayList<>();
        int index = 0;
        while (index < lines.size()) {
            Fence openingFence = fence(lines.get(index));
            if (openingFence != null) {
                CodeResult code = code(raw, lines, index, openingFence);
                blocks.add(code.block());
                index = code.end();
            } else if (NotionMarkdownTable.startsAt(lines, index)) {
                NotionMarkdownTable.Result table = NotionMarkdownTable.parse(lines, index);
                blocks.addAll(table.blocks());
                index = table.end();
            } else {
                if (!lines.get(index).isBlank()) blocks.add(lineBlock(lines.get(index)));
                index++;
            }
        }
        return json.writeValueAsString(blocks);
    }

    private static String withoutEnding(String line) {
        int end = line.length();
        if (end >= 2 && line.charAt(end - 2) == '\r' && line.charAt(end - 1) == '\n') end -= 2;
        else if (end > 0 && lineBreak(line.charAt(end - 1))) end--;
        return line.substring(0, end);
    }

    private static Map<String, Object> lineBlock(String line) {
        if (divider(line)) return block("divider", Map.of());
        int start = upToThreeSpaces(line);
        Heading heading = heading(line, start);
        if (heading != null) return textBlock("heading_" + heading.level(), line.substring(heading.contentStart()));
        int content = listContentStart(line);
        if (content >= 0) return textBlock("bulleted_list_item", line.substring(content));
        content = numberedContentStart(line);
        if (content >= 0) return textBlock("numbered_list_item", line.substring(content));
        if (start < line.length() && line.charAt(start) == '>') {
            content = start + 1;
            if (content < line.length() && horizontalSpace(line.charAt(content))) content++;
            return textBlock("quote", line.substring(content));
        }
        return textBlock("paragraph", line);
    }

    private static List<String> splitLines(String markdown) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        int cursor = 0;
        while (cursor < markdown.length()) {
            int breakLength = lineBreakLength(markdown, cursor);
            if (breakLength > 0) {
                cursor += breakLength;
                lines.add(markdown.substring(start, cursor));
                start = cursor;
            } else {
                cursor++;
            }
        }
        lines.add(markdown.substring(start));
        return lines;
    }

    private static int upToThreeSpaces(String line) {
        int cursor = 0;
        while (cursor < Math.min(3, line.length()) && line.charAt(cursor) == ' ') cursor++;
        return cursor;
    }

    private static Heading heading(String line, int start) {
        int cursor = start;
        while (cursor < line.length() && cursor - start < 3 && line.charAt(cursor) == '#') cursor++;
        int level = cursor - start;
        if (level == 0 || cursor >= line.length() || !horizontalSpace(line.charAt(cursor))) return null;
        while (cursor < line.length() && horizontalSpace(line.charAt(cursor))) cursor++;
        return new Heading(level, cursor);
    }

    private static int listContentStart(String line) {
        int cursor = leadingWhitespace(line);
        if (cursor >= line.length() || "-*+".indexOf(line.charAt(cursor)) < 0) return -1;
        return skipRequiredHorizontalSpace(line, cursor + 1);
    }

    private static int numberedContentStart(String line) {
        int cursor = leadingWhitespace(line);
        int digits = cursor;
        while (cursor < line.length() && Character.isDigit(line.charAt(cursor))) cursor++;
        if (cursor == digits || cursor >= line.length() || ".)".indexOf(line.charAt(cursor)) < 0) return -1;
        return skipRequiredHorizontalSpace(line, cursor + 1);
    }

    private static int leadingWhitespace(String line) {
        int cursor = 0;
        while (cursor < line.length() && regexWhitespace(line.charAt(cursor))) cursor++;
        return cursor;
    }

    private static boolean regexWhitespace(char value) {
        return value == ' ' || value == '\t' || value == '\f' || value == '\u000B';
    }

    private static int lineBreakLength(String value, int cursor) {
        char current = value.charAt(cursor);
        if (current == '\r' && cursor + 1 < value.length() && value.charAt(cursor + 1) == '\n') return 2;
        return lineBreak(current) ? 1 : 0;
    }

    private static boolean lineBreak(char value) {
        return value == '\r' || value == '\n' || value == '\u000B' || value == '\f'
                || value == '\u0085' || value == '\u2028' || value == '\u2029';
    }

    private static int skipRequiredHorizontalSpace(String line, int cursor) {
        if (cursor >= line.length() || !horizontalSpace(line.charAt(cursor))) return -1;
        while (cursor < line.length() && horizontalSpace(line.charAt(cursor))) cursor++;
        return cursor;
    }

    private static boolean horizontalSpace(char value) {
        return value == ' ' || value == '\t';
    }

    private static boolean divider(String line) {
        String candidate = line.strip();
        if (candidate.isEmpty()) return false;
        char marker = candidate.charAt(0);
        if (marker != '-' && marker != '*' && marker != '_') return false;
        int count = 0;
        int cursor = 0;
        while (cursor < candidate.length()) {
            char value = candidate.charAt(cursor++);
            if (value == marker) count++;
            else if (!horizontalSpace(value)) return false;
        }
        return count >= 3;
    }

    private static Map<String, Object> textBlock(String type, String text) {
        return block(type, Map.of(RICH_TEXT, NotionMarkdownInline.parse(text)));
    }

    static Map<String, Object> block(String type, Map<String, ?> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("object", "block");
        result.put("type", type);
        result.put(type, new TreeMap<>(body));
        return result;
    }

    private static CodeResult code(List<String> raw, List<String> lines, int start, Fence fence) {
        String delimiter = fence.delimiter();
        String language = language(fence.info().strip());
        StringBuilder content = new StringBuilder();
        int end = start + 1;
        while (end < lines.size() && !closesFence(lines.get(end), delimiter)) {
            content.append(raw.get(end++));
        }
        if (end < lines.size()) end++;
        return new CodeResult(block("code", Map.of(
                RICH_TEXT, NotionMarkdownInline.literal(content.toString()), "language", language)), end);
    }

    private static boolean closesFence(String line, String delimiter) {
        Fence candidate = fence(line.stripTrailing());
        return candidate != null && candidate.info().isBlank()
                && candidate.delimiter().charAt(0) == delimiter.charAt(0)
                && candidate.delimiter().length() >= delimiter.length();
    }

    private static Fence fence(String line) {
        int cursor = upToThreeSpaces(line);
        if (cursor >= line.length() || (line.charAt(cursor) != '`' && line.charAt(cursor) != '~')) return null;
        char marker = line.charAt(cursor);
        int start = cursor;
        while (cursor < line.length() && line.charAt(cursor) == marker) cursor++;
        if (cursor - start < 3) return null;
        return new Fence(line.substring(start, cursor), line.substring(cursor));
    }

    private static String language(String info) {
        String token = info.toLowerCase(Locale.ROOT);
        if (LANGUAGES.contains(token)) return token;
        int whitespace = firstWhitespace(token);
        if (whitespace >= 0) token = token.substring(0, whitespace);
        token = LANGUAGE_ALIASES.getOrDefault(token, token);
        return LANGUAGES.contains(token) ? token : PLAIN_TEXT;
    }

    private static int firstWhitespace(String value) {
        int cursor = 0;
        while (cursor < value.length() && !Character.isWhitespace(value.charAt(cursor))) cursor++;
        return cursor < value.length() ? cursor : -1;
    }

    private record Fence(String delimiter, String info) { }
    private record Heading(int level, int contentStart) { }
    private record CodeResult(Map<String, Object> block, int end) { }
}
