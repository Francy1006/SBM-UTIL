package com.sbm.util.integration.notion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotionMarkdownBlockMapperTests {

    private final NotionMarkdownBlockMapper mapper = new NotionMarkdownBlockMapper();

    @Test
    void mapsHeadingOne() {
        assertThat(mapper.toChildrenJson("# Title"))
                .contains("\"type\":\"heading_1\"")
                .contains("\"content\":\"Title\"");
    }

    @Test
    void mapsHeadingTwo() {
        assertThat(mapper.toChildrenJson("## Title"))
                .contains("\"type\":\"heading_2\"")
                .contains("\"content\":\"Title\"");
    }

    @Test
    void mapsHeadingThree() {
        assertThat(mapper.toChildrenJson("### Title"))
                .contains("\"type\":\"heading_3\"")
                .contains("\"content\":\"Title\"");
    }

    @Test
    void mapsParagraph() {
        assertThat(mapper.toChildrenJson("A paragraph"))
                .contains("\"type\":\"paragraph\"")
                .contains("\"content\":\"A paragraph\"");
    }

    @Test
    void ignoresEmptyLines() {
        String renderedJson = mapper.toChildrenJson("First\n\nSecond");

        assertThat(renderedJson).contains("\"content\":\"First\"")
                .contains("\"content\":\"Second\"");
        assertThat(renderedJson).doesNotContain("\"content\":\"\"");
    }

    @Test
    void preservesMixedDocumentOrder() {
        String renderedJson = mapper.toChildrenJson("# First\nParagraph\n### Third");

        assertThat(renderedJson.indexOf("heading_1"))
                .isLessThan(renderedJson.indexOf("Paragraph"));
        assertThat(renderedJson.indexOf("Paragraph"))
                .isLessThan(renderedJson.indexOf("heading_3"));
    }
    private final tools.jackson.databind.json.JsonMapper json = new tools.jackson.databind.json.JsonMapper();

    private tools.jackson.databind.JsonNode blocks(String markdown) {
        return json.readTree(mapper.toChildrenJson(markdown));
    }

    private String text(tools.jackson.databind.JsonNode richText) {
        StringBuilder result = new StringBuilder();
        richText.forEach(segment -> result.append(segment.path("text").path("content").asText()));
        return result.toString();
    }

    @Test
    void mapsInlineStylesAndPreservesPlainSegments() {
        var rich = blocks("before `code` **bold** *italic* _other_ ~~gone~~ after")
                .get(0).path("paragraph").path("rich_text");
        assertThat(text(rich)).isEqualTo("before code bold italic other gone after");
        assertThat(rich.get(0).path("text").path("content").asText()).isEqualTo("before ");
        String[] annotations = {"code", "bold", "italic", "italic", "strikethrough"};
        for (int index = 0; index < annotations.length; index++) {
            assertThat(rich.get(index * 2 + 1).path("annotations").path(annotations[index]).asBoolean()).isTrue();
        }
        assertThat(rich.get(10).path("text").path("content").asText()).isEqualTo(" after");
    }

    @Test
    void doesNotParseMarkdownInsideInlineCode() {
        var rich = blocks("`**bold** *italic* [link](https://example.com)`")
                .get(0).path("paragraph").path("rich_text");
        assertThat(rich.size()).isEqualTo(1);
        assertThat(text(rich)).isEqualTo("**bold** *italic* [link](https://example.com)");
        assertThat(rich.get(0).path("annotations").path("code").asBoolean()).isTrue();
        assertThat(rich.get(0).path("annotations").path("bold").asBoolean()).isFalse();
        assertThat(rich.get(0).path("text").has("link")).isFalse();
    }

    @Test
    void mapsLinksWithBalancedParenthesesAndFormattedLabels() {
        var rich = blocks("[**site**](https://example.com/a_(b))")
                .get(0).path("paragraph").path("rich_text");
        assertThat(text(rich)).isEqualTo("site");
        assertThat(rich.get(0).path("text").path("link").path("url").asText())
                .isEqualTo("https://example.com/a_(b)");
        assertThat(rich.get(0).path("annotations").path("bold").asBoolean()).isTrue();
        var invalid = blocks("[site](javascript:alert(1))").get(0).path("paragraph").path("rich_text");
        assertThat(text(invalid)).isEqualTo("[site](javascript:alert(1))");
        assertThat(invalid.get(0).path("text").has("link")).isFalse();
    }

    @Test
    void sharesInlineParserAcrossHeadingsListsAndQuotes() {
        String[] source = {"# `code`", "## `code`", "### `code`", "- `code`", "* `code`", "+ `code`",
                "1. `code`", "2. `code`", "> `code`"};
        String[] types = {"heading_1", "heading_2", "heading_3", "bulleted_list_item", "bulleted_list_item",
                "bulleted_list_item", "numbered_list_item", "numbered_list_item", "quote"};
        for (int index = 0; index < source.length; index++) {
            var block = blocks(source[index]).get(0);
            assertThat(block.path("type").asText()).isEqualTo(types[index]);
            var rich = block.path(types[index]).path("rich_text");
            assertThat(text(rich)).isEqualTo("code");
            assertThat(rich.get(0).path("annotations").path("code").asBoolean()).isTrue();
        }
    }

    @Test
    void mapsFencedCodeAndPreservesContentExactly() {
        String content = "  System.out.println(\"test\");\r\n\r\n**literal** `code`\r\n";
        var code = blocks("```java\r\n" + content + "```\r\n").get(0).path("code");
        assertThat(code.path("language").asText()).isEqualTo("java");
        assertThat(text(code.path("rich_text"))).isEqualTo(content);
        assertThat(code.path("rich_text").get(0).has("annotations")).isFalse();
    }

    @Test
    void usesPlainTextForMissingOrUnknownFenceLanguages() {
        for (String language : new String[]{"", "unknown-language"}) {
            var code = blocks("```" + language + "\nx\n```").get(0).path("code");
            assertThat(code.path("language").asText()).isEqualTo("plain text");
            assertThat(text(code.path("rich_text"))).isEqualTo("x\n");
        }
        assertThat(blocks("```js\nx\n```").get(0).path("code").path("language").asText())
                .isEqualTo("javascript");
    }

    @Test
    void acceptsLongerFencesAndUnclosedCodeWithoutLosingContent() {
        assertThat(text(blocks("````\n```\n````").get(0).path("code").path("rich_text")))
                .isEqualTo("```\n");
        assertThat(text(blocks("~~~\nlast line").get(0).path("code").path("rich_text")))
                .isEqualTo("last line");
    }

    @Test
    void mapsTwoColumnTableWithHeaderAndEmptyCells() {
        var table = blocks("| A | B |\n|---|---|\n| x | |\n| | y |").get(0).path("table");
        assertThat(table.path("table_width").asInt()).isEqualTo(2);
        assertThat(table.path("has_column_header").asBoolean()).isTrue();
        assertThat(table.path("has_row_header").asBoolean()).isFalse();
        var rows = table.path("children");
        assertThat(rows.size()).isEqualTo(3);
        assertThat(rows.get(0).path("type").asText()).isEqualTo("table_row");
        assertThat(text(rows.get(0).path("table_row").path("cells").get(0))).isEqualTo("A");
        assertThat(rows.get(1).path("table_row").path("cells").get(1).size()).isZero();
        assertThat(rows.get(2).path("table_row").path("cells").get(0).size()).isZero();
    }

    @Test
    void hidesAlignmentSeparatorsAndNormalizesUnevenRows() {
        var table = blocks("A | B | C\n:--- | ---: | :---:\nx | y\na | b | c | d")
                .get(0).path("table");
        assertThat(table.path("table_width").asInt()).isEqualTo(4);
        assertThat(table.path("children").size()).isEqualTo(3);
        for (var row : table.path("children")) {
            assertThat(row.path("table_row").path("cells").size()).isEqualTo(4);
            assertThat(row.toString()).doesNotContain("---");
        }
    }

    @Test
    void parsesCellFormattingAndProtectsCodeAndEscapedPipes() {
        var table = blocks("""
                A | B
                --- | ---
                `a|b` | **bold**
                ``a`|b`` | a\\|b
                *italic* | [site](https://example.com)""").get(0).path("table");
        assertThat(table.path("table_width").asInt()).isEqualTo(2);
        var first = table.path("children").get(1).path("table_row").path("cells");
        assertThat(text(first.get(0))).isEqualTo("a|b");
        assertThat(first.get(0).get(0).path("annotations").path("code").asBoolean()).isTrue();
        assertThat(first.get(1).get(0).path("annotations").path("bold").asBoolean()).isTrue();
        var second = table.path("children").get(2).path("table_row").path("cells");
        assertThat(text(second.get(0))).isEqualTo("a`|b");
        assertThat(text(second.get(1))).isEqualTo("a|b");
        var third = table.path("children").get(3).path("table_row").path("cells");
        assertThat(third.get(0).get(0).path("annotations").path("italic").asBoolean()).isTrue();
        assertThat(third.get(1).get(0).path("text").path("link").path("url").asText())
                .isEqualTo("https://example.com");
    }

    @Test
    void mapsDividerOutsideTablesAndSkipsWhitespaceLines() {
        var result = blocks("---\n  \n***\n\t\n___\n> quote");
        assertThat(result.size()).isEqualTo(4);
        for (int index = 0; index < 3; index++) {
            assertThat(result.get(index).path("type").asText()).isEqualTo("divider");
            assertThat(result.get(index).path("divider").size()).isZero();
        }
        assertThat(text(result.get(3).path("quote").path("rich_text"))).isEqualTo("quote");
    }

    @Test
    void escapesJsonAndPreservesUnformattedText() {
        String source = "A \"quote\", C:\\folder, tab\tand emoji 😀";
        assertThat(text(blocks(source).get(0).path("paragraph").path("rich_text"))).isEqualTo(source);
    }

    @Test
    void generatesDeterministicJson() {
        String source = "## **Title**\n\nA | B\n--- | ---\n`code` | [link](https://example.com)";
        assertThat(mapper.toChildrenJson(source)).isEqualTo(mapper.toChildrenJson(source))
                .isEqualTo(new NotionMarkdownBlockMapper().toChildrenJson(source));
    }

    @Test
    void mapsContextDocumentationProductionRegression() {
        String source = """
                ## 7. Estado validado de Context y Documentation
                | Objective ID | Project | Objective | Status | Validation |
                |---|---|---|---|---|
                | OBJ-CTX-013 | SBM-SUITE | Corregir y validar el workflow de documentación de `SBM-SUITE/context`, incluyendo `documentation-deploy.sh` y `documentation-upgrade.sh` | completed | `implementation-closure` y `context-upgrade` completados |
                """;
        var result = blocks(source);
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.get(0).path("type").asText()).isEqualTo("heading_2");
        assertThat(result.get(1).path("type").asText()).isEqualTo("table");
        var table = result.get(1).path("table");
        assertThat(table.path("table_width").asInt()).isEqualTo(5);
        assertThat(table.path("has_column_header").asBoolean()).isTrue();
        assertThat(table.path("children").size()).isEqualTo(2);
        java.util.List<String> code = new java.util.ArrayList<>();
        for (var row : table.path("children")) {
            for (var cell : row.path("table_row").path("cells")) {
                assertThat(text(cell)).doesNotContain("|", "---");
                for (var segment : cell) {
                    if (segment.path("annotations").path("code").asBoolean()) {
                        code.add(segment.path("text").path("content").asText());
                    }
                }
            }
        }
        assertThat(code).containsExactly("SBM-SUITE/context", "documentation-deploy.sh",
                "documentation-upgrade.sh", "implementation-closure", "context-upgrade");
    }

    @Test
    void splitsLongTextWithoutBreakingUnicode() {
        String content = "a".repeat(1999) + "😀tail";
        var rich = blocks(content).get(0).path("paragraph").path("rich_text");
        assertThat(rich.size()).isEqualTo(2);
        assertThat(text(rich)).isEqualTo(content);
        for (var segment : rich) assertThat(segment.path("text").path("content").asText().length()).isLessThanOrEqualTo(2000);
    }

    @Test
    void splitsLargeTablesIntoNativeTablesWithRepeatedHeaders() {
        String source = "A | B\n--- | ---\n" + "x | y\n".repeat(101);
        var result = blocks(source);
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.get(0).path("table").path("children").size()).isEqualTo(100);
        assertThat(result.get(1).path("table").path("children").size()).isEqualTo(3);
    }
}
