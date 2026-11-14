package com.nekocyte.companion;

import android.os.Build;
import android.text.Html;
import android.text.Spanned;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 轻量 Markdown 渲染：标题、粗体、斜体、行内代码、链接、列表、引用、代码块。 */
public class Markdown {
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\(([^)]+)\\)");
    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final Pattern ITALIC = Pattern.compile("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)");
    private static final Pattern CODE = Pattern.compile("`([^`]+)`");

    public static Spanned render(String md) {
        String html = toHtml(md == null ? "" : md);
        if (Build.VERSION.SDK_INT >= 24) {
            return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY);
        }
        return Html.fromHtml(html);
    }

    private static String toHtml(String md) {
        String[] lines = md.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean inCode = false;
        StringBuilder code = new StringBuilder();
        boolean inUl = false;
        boolean inOl = false;

        for (String raw : lines) {
            String line = raw == null ? "" : raw;
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                closeLists(out, inUl, inOl);
                inUl = false;
                inOl = false;
                if (inCode) {
                    out.append("<pre><code>").append(escape(code.toString())).append("</code></pre>");
                    code.setLength(0);
                }
                inCode = !inCode;
                continue;
            }
            if (inCode) {
                code.append(line).append('\n');
                continue;
            }
            if (trimmed.isEmpty()) {
                closeLists(out, inUl, inOl);
                inUl = false;
                inOl = false;
                out.append("<br>");
                continue;
            }
            if (trimmed.startsWith("#")) {
                closeLists(out, inUl, inOl);
                inUl = false;
                inOl = false;
                int level = 0;
                while (level < trimmed.length() && trimmed.charAt(level) == '#') level++;
                level = Math.max(1, Math.min(3, level));
                out.append("<h").append(level).append(">")
                        .append(inline(trimmed.substring(level).trim()))
                        .append("</h").append(level).append(">");
                continue;
            }
            if (trimmed.startsWith(">")) {
                closeLists(out, inUl, inOl);
                inUl = false;
                inOl = false;
                out.append("<blockquote>").append(inline(trimmed.substring(1).trim())).append("</blockquote>");
                continue;
            }
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                if (!inUl) {
                    closeLists(out, inUl, inOl);
                    out.append("<ul>");
                    inUl = true;
                    inOl = false;
                }
                out.append("<li>").append(inline(trimmed.substring(2))).append("</li>");
                continue;
            }
            Matcher ordered = Pattern.compile("^\\d+[.)]\\s+(.*)$").matcher(trimmed);
            if (ordered.matches()) {
                if (!inOl) {
                    closeLists(out, inUl, inOl);
                    out.append("<ol>");
                    inOl = true;
                    inUl = false;
                }
                out.append("<li>").append(inline(ordered.group(1))).append("</li>");
                continue;
            }
            closeLists(out, inUl, inOl);
            inUl = false;
            inOl = false;
            out.append(inline(trimmed)).append("<br>");
        }
        if (inCode && code.length() > 0) {
            out.append("<pre><code>").append(escape(code.toString())).append("</code></pre>");
        }
        closeLists(out, inUl, inOl);
        return out.toString();
    }

    private static void closeLists(StringBuilder out, boolean ul, boolean ol) {
        if (ul) out.append("</ul>");
        if (ol) out.append("</ol>");
    }

    private static String inline(String text) {
        String value = escape(text);
        value = LINK.matcher(value).replaceAll("<a href=\"$2\">$1</a>");
        value = BOLD.matcher(value).replaceAll("<b>$1</b>");
        value = ITALIC.matcher(value).replaceAll("<i>$1</i>");
        value = CODE.matcher(value).replaceAll("<code>$1</code>");
        return value;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
