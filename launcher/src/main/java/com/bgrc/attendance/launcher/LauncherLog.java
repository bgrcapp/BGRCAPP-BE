package com.bgrc.attendance.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;

/** 서버 로그와 분리해 launcher의 업데이트 판단·롤백만 남긴다. */
final class LauncherLog {
    private final Path path;

    LauncherLog(Path root) throws IOException {
        path = root.resolve("data").resolve("logs").resolve("updater.log");
        Files.createDirectories(path.getParent());
    }

    synchronized void info(String message) {
        String line = OffsetDateTime.now() + " [UPDATER] " + message + System.lineSeparator();
        System.out.print(line);
        try {
            Files.writeString(path, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            System.err.println("업데이트 로그 파일 기록 실패: " + path);
        }
    }
}
