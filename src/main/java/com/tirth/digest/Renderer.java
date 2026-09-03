package com.tirth.digest;

import com.tirth.digest.model.Line;
import com.tirth.digest.model.Section;

import java.util.List;

public final class Renderer {

    // Mail clients strip <style> blocks and external stylesheets, so every rule is inline.
    private static final String PAGE =
            "font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;"
                    + "max-width:640px;margin:0 auto;padding:24px 20px;color:#1a1a1a;line-height:1.5";
    private static final String HEADING =
            "font-size:12px;font-weight:600;letter-spacing:0.08em;text-transform:uppercase;"
                    + "color:#6b6b6b;margin:28px 0 8px;padding-bottom:6px;border-bottom:1px solid #e5e5e5";
    private static final String ITEM = "margin:6px 0;font-size:15px";
    private static final String LINK = "color:#1a1a1a;text-decoration:none;border-bottom:1px solid #c9c9c9";

    private Renderer() {
    }

    public static String plainText(List<Section> sections) {
        StringBuilder out = new StringBuilder();
        for (Section section : sections) {
            out.append(section.title()).append('\n');
            for (Line line : section.lines()) {
                out.append("  ").append(line.text()).append('\n');
            }
            out.append('\n');
        }
        return out.toString();
    }

    public static String html(List<Section> sections) {
        StringBuilder out = new StringBuilder("<div style=\"").append(PAGE).append("\">");
        for (Section section : sections) {
            out.append("<div style=\"").append(HEADING).append("\">")
                    .append(escape(section.title()))
                    .append("</div>");
            for (Line line : section.lines()) {
                out.append("<div style=\"").append(ITEM).append("\">")
                        .append(renderLine(line))
                        .append("</div>");
            }
        }
        return out.append("</div>").toString();
    }

    private static String renderLine(Line line) {
        String text = escape(line.text());
        if (!line.hasLink()) {
            return text;
        }
        return "<a href=\"%s\" style=\"%s\">%s</a>".formatted(escape(line.link()), LINK, text);
    }

    private static String escape(String raw) {
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
