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
        String json = mapper.toChildrenJson("First\n\nSecond");

        assertThat(json).contains("\"content\":\"First\"")
                .contains("\"content\":\"Second\"");
        assertThat(json).doesNotContain("\"content\":\"\"");
    }

    @Test
    void preservesMixedDocumentOrder() {
        String json = mapper.toChildrenJson("# First\nParagraph\n### Third");

        assertThat(json.indexOf("heading_1"))
                .isLessThan(json.indexOf("Paragraph"));
        assertThat(json.indexOf("Paragraph"))
                .isLessThan(json.indexOf("heading_3"));
    }
}