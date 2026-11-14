package com.nekocyte.companion;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 只识别 B 站视频和抖音视频链接，不识别图文/动态。 */
public class VideoLink {
    private static final Pattern BILI = Pattern.compile(
            "https?://(?:www\\.|m\\.)?bilibili\\.com/video/(BV[0-9A-Za-z]+|av\\d+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DOUYIN = Pattern.compile(
            "https?://(?:www\\.)?(?:douyin\\.com/video/|iesdouyin\\.com/share/video/)(\\d+)",
            Pattern.CASE_INSENSITIVE);

    public final String platform;
    public final String id;
    public final String original;
    public final String playerUrl;

    private VideoLink(String platform, String id, String original, String playerUrl) {
        this.platform = platform;
        this.id = id;
        this.original = original;
        this.playerUrl = playerUrl;
    }

    public static VideoLink find(String text) {
        if (text == null || text.isEmpty()) return null;
        Matcher bili = BILI.matcher(text);
        if (bili.find()) {
            String id = bili.group(1);
            String key = id.toLowerCase().startsWith("av") ? "aid" : "bvid";
            String url = "https://player.bilibili.com/player.html?" + key + "=" + id
                    + "&autoplay=0&high_quality=1&danmaku=0";
            return new VideoLink("哔哩哔哩", id, bili.group(0), url);
        }
        Matcher douyin = DOUYIN.matcher(text);
        if (douyin.find()) {
            String id = douyin.group(1);
            String url = "https://www.iesdouyin.com/share/video/" + id + "/?region=CN&autoplay=0";
            return new VideoLink("抖音", id, douyin.group(0), url);
        }
        return null;
    }

    public static String strip(String text, VideoLink link) {
        if (text == null || link == null) return text == null ? "" : text;
        return text.replace(link.original, "").replaceAll("[ \\t]+\\n", "\n").trim();
    }
}
