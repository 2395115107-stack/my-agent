package com.myagent.plugin.builtin;

import com.myagent.plugin.AgentPlugin;
import com.myagent.plugin.PluginConfigField;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 内置插件「网页抓取」(实验性):抓取公开网页正文供调研类任务引用事实来源。
 * 边界:仅 http/https;拒绝本机/内网地址(SSRF 防护),重定向逐跳复检;
 * 超时与输出长度可配,响应体超限即截断 —— 这个工具出网,所以标 experimental。
 */
@Component
public class WebFetchPlugin implements AgentPlugin {

    private static final int MAX_BODY_BYTES = 512 * 1024;
    private static final int MAX_REDIRECTS = 3;
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    @Override
    public String key() {
        return "plugin-web-fetch";
    }

    @Override
    public String title() {
        return "网页抓取";
    }

    @Override
    public String description() {
        return "抓取公开网页并返回去标签后的正文(可配置超时与长度上限),调研类任务引用外部事实来源用。";
    }

    @Override
    public boolean experimental() {
        return true;
    }

    @Override
    public List<PluginConfigField> configFields() {
        return List.of(
                PluginConfigField.number("timeoutSeconds", "超时(秒)", 10, 1, 60, "单次请求的最长等待时间"),
                PluginConfigField.number("maxChars", "正文长度上限(字符)", 4000, 200, 20000, "超出截断,避免撑爆上下文"));
    }

    @Override
    public List<Object> toolBeans(Map<String, Object> config) {
        double timeout = asDouble(config.get("timeoutSeconds"), 10);
        int maxChars = (int) asDouble(config.get("maxChars"), 4000);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds((long) Math.ceil(timeout)))
                .followRedirects(HttpClient.Redirect.NEVER) // 重定向手动跟,逐跳复检 SSRF
                .build();
        return List.of(new Tools(client, timeout, maxChars));
    }

    private static double asDouble(Object raw, double fallback) {
        return raw instanceof Number n && Double.isFinite(n.doubleValue()) ? n.doubleValue() : fallback;
    }

    record Tools(HttpClient client, double timeoutSeconds, int maxChars) {

        @Tool(description = "抓取公开网页并返回去标签的正文文本(开头带最终 URL 与标题)。仅用于公开 http/https 页面")
        public String fetchUrl(@ToolParam(description = "要抓取的网页 URL") String url) {
            URI uri;
            try {
                uri = URI.create(url.trim());
            } catch (Exception e) {
                return "错误:URL 无法解析";
            }
            try {
                Fetcher f = new Fetcher(client, timeoutSeconds);
                Fetched fetched = f.fetch(uri);
                String text = HtmlText.of(fetched.body(), maxChars);
                return "url=" + fetched.uri() + "\ntitle=" + fetched.title() + "\n----\n" + text;
            } catch (Exception e) {
                String msg = e.getMessage();
                return "错误:" + (msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg);
            }
        }
    }

    private record Fetched(URI uri, String title, String body) {
    }

    /** 请求循环:每跳都过 SSRF 门禁,最多跟 3 次重定向;响应体读满 512KB 即停。 */
    private static final class Fetcher {
        private final HttpClient client;
        private final double timeoutSeconds;

        Fetcher(HttpClient client, double timeoutSeconds) {
            this.client = client;
            this.timeoutSeconds = timeoutSeconds;
        }

        Fetched fetch(URI start) throws IOException, InterruptedException {
            URI current = start;
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                guard(current);
                HttpRequest request = HttpRequest.newBuilder(current)
                        .timeout(Duration.ofMillis((long) (timeoutSeconds * 1000)))
                        .header("User-Agent", "my-agent-plugin-webfetch/0.1")
                        .header("Accept", "text/html,text/plain,application/json;q=0.9,*/*;q=0.1")
                        .GET()
                        .build();
                HttpResponse<InputStream> resp = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = resp.statusCode();
                if (status >= 300 && status < 400) {
                    String location = resp.headers().firstValue("Location").orElse(null);
                    try (InputStream in = resp.body()) { /* 丢弃中间响应体 */ }
                    if (location == null) {
                        throw new IOException("重定向缺少 Location 头");
                    }
                    current = current.resolve(location);
                    continue;
                }
                if (status != 200) {
                    throw new IOException("HTTP " + status);
                }
                String type = resp.headers().firstValue("Content-Type").orElse("");
                if (!(type.toLowerCase(Locale.ROOT).contains("html") || type.contains("text/plain") || type.contains("json"))) {
                    try (InputStream in = resp.body()) { }
                    throw new IOException("不支持的响应类型: " + type.split(";")[0].trim());
                }
                String body = readCapped(resp.body());
                String title = HtmlText.title(body);
                return new Fetched(current, title, body);
            }
            throw new IOException("重定向次数超过 " + MAX_REDIRECTS);
        }

        private static String readCapped(InputStream in) throws IOException {
            byte[] buf = new byte[8192];
            var out = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = in.read(buf)) >= 0 && out.size() < MAX_BODY_BYTES) {
                out.write(buf, 0, Math.min(n, MAX_BODY_BYTES - out.size()));
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** SSRF 门禁:仅 http/https;主机解析后的任一地址落在本机/内网/链路本地即拒绝。 */
    private static void guard(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw new IllegalArgumentException("仅支持 http/https");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL 缺少主机名");
        }
        try {
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (addr.isLoopbackAddress() || addr.isSiteLocalAddress()
                        || addr.isAnyLocalAddress() || addr.isLinkLocalAddress()) {
                    throw new IllegalArgumentException("不允许访问本机/内网地址: " + host);
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("主机无法解析: " + host);
        }
    }
}

/** HTML → 纯文本:去 script/style,标签转空格,常见实体解码,空白折叠。 */
final class HtmlText {

    private HtmlText() {
    }

    static String title(String html) {
        var m = java.util.regex.Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
        return m.find() ? collapse(m.group(1)).trim() : "";
    }

    static String of(String html, int maxChars) {
        String s = html.replaceAll("(?is)<(script|style|noscript|svg)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?is)<!--.*?-->", " ")
                .replaceAll("(?i)<(br|/p|/div|/li|/h[1-6]|/tr)[^>]*>", "\n")
                .replaceAll("<[^>]+>", " ");
        s = collapse(s);
        if (s.length() > maxChars) {
            s = s.substring(0, maxChars) + "\n…(已截断,原文更长)";
        }
        return s;
    }

    private static String collapse(String s) {
        return s.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replaceAll("[\\s&&[^\\n]]+", " ")
                .replaceAll(" ?\\n ?", "\n")
                .replaceAll("\\n{2,}", "\n")
                .trim();
    }
}
