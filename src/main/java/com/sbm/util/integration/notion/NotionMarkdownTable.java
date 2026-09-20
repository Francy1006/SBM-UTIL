package com.sbm.util.integration.notion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class NotionMarkdownTable {
    private NotionMarkdownTable() { }

    static boolean startsAt(List<String> lines, int index) {
        if (index + 1 >= lines.size()) return false;
        List<String> header = cells(lines.get(index));
        List<String> separator = cells(lines.get(index + 1));
        return !header.isEmpty() && header.size() == separator.size()
                && separator.stream().allMatch(NotionMarkdownTable::alignmentCell);
    }

    static Result parse(List<String> lines, int start) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(cells(lines.get(start)));
        int end = start + 2;
        while (end < lines.size()) {
            List<String> row = cells(lines.get(end));
            if (row.isEmpty()) break;
            rows.add(row);
            end++;
        }
        int width = rows.stream().mapToInt(List::size).max().orElseThrow();
        List<Map<String, Object>> blocks = new ArrayList<>();
        // Each native table has at most 100 child rows; repeat its header on continuation tables.
        for (int offset = 1; offset < Math.max(2, rows.size()); offset += 99) {
            List<Map<String, Object>> children = new ArrayList<>();
            children.add(rowBlock(rows.getFirst(), width));
            for (int index = offset; index < Math.min(offset + 99, rows.size()); index++) {
                children.add(rowBlock(rows.get(index), width));
            }
            blocks.add(NotionMarkdownBlockMapper.block("table", Map.of(
                    "table_width", width, "has_column_header", true,
                    "has_row_header", false, "children", children)));
        }
        return new Result(blocks, end);
    }

    private static Map<String, Object> rowBlock(List<String> row, int width) {
        List<Object> cells = new ArrayList<>();
        for (int index = 0; index < width; index++) {
            cells.add(NotionMarkdownInline.parse(index < row.size() ? row.get(index) : ""));
        }
        return NotionMarkdownBlockMapper.block("table_row", Map.of("cells", cells));
    }

    static List<String> cells(String source) {
        String line = source.strip();
        List<String> result = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean separated = false;
        int index = 0;
        while (index < line.length()) {
            char value = line.charAt(index);
            if (value == '\\' && index + 1 < line.length()) {
                cell.append(value).append(line.charAt(index + 1));
                index += 2;
            } else if (value == '`' && NotionMarkdownInline.codeEnd(line, index) >= 0) {
                int end = NotionMarkdownInline.codeEnd(line, index)
                        + NotionMarkdownInline.runLength(line, index, '`');
                cell.append(line, index, end);
                index = end;
            } else if (value == '|') {
                result.add(cell.toString().strip());
                cell.setLength(0);
                separated = true;
                index++;
            } else {
                cell.append(value);
                index++;
            }
        }
        if (!separated) return List.of();
        result.add(cell.toString().strip());
        if (line.startsWith("|")) result.removeFirst();
        // A protected trailing pipe belongs to the final cell, not the table boundary.
        if (cell.isEmpty() && line.endsWith("|")) result.removeLast();
        return result;
    }

    private static boolean alignmentCell(String cell) {
        int start = cell.startsWith(":") ? 1 : 0;
        int end = cell.endsWith(":") ? cell.length() - 1 : cell.length();
        if (end - start < 3) return false;
        int cursor = start;
        while (cursor < end && cell.charAt(cursor) == '-') cursor++;
        return cursor == end;
    }

    record Result(List<Map<String, Object>> blocks, int end) { }
}
