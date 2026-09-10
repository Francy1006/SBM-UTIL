package com.sbm.util.integration.notion;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class NotionMarkdownBlockMapper {

    private final tools.jackson.databind.json.JsonMapper json = new tools.jackson.databind.json.JsonMapper();

    public String toChildrenJson(String markdown) {
        List<String> blocks = new ArrayList<>();

        for (String line : markdown.split("\\R", -1)) {
            if (line.isEmpty()) {
                continue;
            }

            String type = "paragraph";
            String text = line;
            if (line.startsWith("### ")) {
                type = "heading_3";
                text = line.substring(4);
            } else if (line.startsWith("## ")) {
                type = "heading_2";
                text = line.substring(3);
            } else if (line.startsWith("# ")) {
                type = "heading_1";
                text = line.substring(2);
            }

            blocks.add(blockJson(type, text));
        }

        return "[" + String.join(",", blocks) + "]";
    }

    private String blockJson(String type, String text) {
        var block = json.createObjectNode();
        block.put("object", "block");
        block.put("type", type);
        block.putObject(type).putArray("rich_text").addObject()
                .put("type", "text").putObject("text").put("content", text);
        return json.writeValueAsString(block);
    }
}
