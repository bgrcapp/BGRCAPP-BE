package com.bgrc.attendance.launcher;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 서명 검증을 통과한 manifest.json의 필요한 값만 표현한다. */
final class UpdateManifest {
    private final String version;
    private final String minimumLauncherVersion;
    private final URI jarUrl;
    private final String sha256;

    private UpdateManifest(String version, String minimumLauncherVersion, URI jarUrl, String sha256) {
        this.version = version;
        this.minimumLauncherVersion = minimumLauncherVersion;
        this.jarUrl = jarUrl;
        this.sha256 = sha256;
    }

    static UpdateManifest parse(String json) {
        String version = stringValue(json, "version", true);
        String minimumLauncherVersion = stringValue(json, "minimumLauncherVersion", false);
        String jarUrl = stringValue(json, "jarUrl", true);
        String sha256 = stringValue(json, "sha256", true).toLowerCase();

        Version.parse(version);
        if (minimumLauncherVersion != null) Version.parse(minimumLauncherVersion);
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("manifest의 sha256 형식이 올바르지 않습니다.");
        }

        URI parsedUrl = URI.create(jarUrl);
        if (!"https".equalsIgnoreCase(parsedUrl.getScheme()) || parsedUrl.getHost() == null) {
            throw new IllegalArgumentException("JAR 다운로드 주소는 HTTPS URL이어야 합니다.");
        }
        return new UpdateManifest(version, minimumLauncherVersion, parsedUrl, sha256);
    }

    static String stringValue(String json, String key, boolean required) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(key)
                + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            if (required) throw new IllegalArgumentException("manifest에 " + key + " 값이 없습니다.");
            return null;
        }
        return unescapeJson(matcher.group(1));
    }

    private static String unescapeJson(String value) {
        return value.replace("\\\\", "\\")
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }

    String version() { return version; }
    String minimumLauncherVersion() { return minimumLauncherVersion; }
    URI jarUrl() { return jarUrl; }
    String sha256() { return sha256; }
}
