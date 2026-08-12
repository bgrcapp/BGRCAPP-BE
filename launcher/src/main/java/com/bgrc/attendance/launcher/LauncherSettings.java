package com.bgrc.attendance.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** 설치 폴더의 config/launcher.properties를 읽고, 성공한 활성 버전만 원자적으로 갱신한다. */
final class LauncherSettings {
    private final Path path;
    private final Properties properties;

    private LauncherSettings(Path path, Properties properties) {
        this.path = path;
        this.properties = properties;
    }

    static LauncherSettings load(Path root) throws IOException {
        Path path = root.resolve("config").resolve("launcher.properties");
        if (!Files.isRegularFile(path)) {
            throw new IOException("launcher 설정 파일을 찾을 수 없습니다: " + path);
        }
        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(path)) {
            properties.load(inputStream);
        }
        return new LauncherSettings(path, properties);
    }

    String required(String key) {
        String value = properties.getProperty(key, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("launcher 설정값이 비어 있습니다: " + key);
        return value;
    }

    String optional(String key, String defaultValue) {
        String value = properties.getProperty(key, "").trim();
        return value.isEmpty() ? defaultValue : value;
    }

    int positiveInt(String key, int defaultValue) {
        String value = optional(key, String.valueOf(defaultValue));
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("launcher 설정값은 양수여야 합니다: " + key);
        }
    }

    Path activeJar(Path root) {
        return resolveRelative(root, required("active.jar"));
    }

    String activeVersion() {
        return required("active.version");
    }

    Path resolveRelative(Path root, String value) {
        Path path = Path.of(value);
        if (path.isAbsolute()) throw new IllegalArgumentException("절대 경로는 허용하지 않습니다: " + value);
        Path resolved = root.resolve(path).normalize();
        if (!resolved.startsWith(root)) throw new IllegalArgumentException("설치 폴더 밖 경로는 허용하지 않습니다: " + value);
        return resolved;
    }

    void saveActiveVersion(Path root, String version, Path jar) throws IOException {
        properties.setProperty("active.version", version);
        properties.setProperty("active.jar", root.relativize(jar).toString().replace('\\', '/'));
        properties.remove("failed.version");
        save();
    }

    void markFailedVersion(String version) throws IOException {
        properties.setProperty("failed.version", version);
        save();
    }

    String failedVersion() {
        return optional("failed.version", "");
    }

    private void save() throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".launcher-", ".properties");
        try (OutputStream outputStream = Files.newOutputStream(temporary)) {
            properties.store(outputStream, "무료급식 출석 서버 launcher 설정");
        }
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
