package com.dove.thegallery;

import java.util.List;

/**
 * The page served by Gallery sharing: a plain grid of the shared images. Clicking one opens the
 * original at /i/&lt;index&gt;; the grid itself loads /t/&lt;index&gt; thumbnails. The browser's own
 * image menu covers copying, so the page carries no controls.
 */
final class SharePage {

    private SharePage() { }

    static String html(List<String> names) {
        StringBuilder page = new StringBuilder(1024);
        page.append("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n")
                .append("<title>The Gallery</title>\n<style>\n").append(STYLE).append("</style>\n</head>\n<body>\n<main>\n");
        for (int index = 0; index < names.size(); index++) {
            page.append("<a href=\"i/").append(index).append("\"><img src=\"t/").append(index)
                    .append("\" alt=\"").append(escape(names.get(index))).append("\" loading=\"lazy\"></a>\n");
        }
        page.append("</main>\n</body>\n</html>\n");
        return page.toString();
    }

    private static String escape(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&#39;"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }

    private static final String STYLE = """
            body{margin:0;background:#13151b}
            main{display:grid;grid-template-columns:repeat(auto-fill,minmax(180px,1fr));gap:2px;padding:2px}
            a{display:block;line-height:0}
            img{display:block;width:100%;height:auto}
            """;
}
