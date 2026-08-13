package com.bgrc.attendance.launcher;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Windows 출석 서버를 실행하는 안정된 부모 프로세스다.
 * 새 JAR는 서버 실행 전에만 적용하며, 실패하면 기존 JAR를 다시 실행한다.
 */
public final class AttendanceLauncher {
    static final String LAUNCHER_VERSION = "1.0.0";
    private static final Pattern JAVA_VERSION_PATTERN = Pattern.compile(
            "(?:java|openjdk)(?:\\s+version)?\\s+\\\"?(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final AtomicReference<Process> ACTIVE_SERVER_PROCESS = new AtomicReference<>();

    private AttendanceLauncher() {
    }

    public static void main(String[] args) {
        Runtime.getRuntime().addShutdownHook(new Thread(AttendanceLauncher::stopActiveServer,
                "attendance-server-cleanup"));
        try {
            Path root = installationRoot(args);
            LauncherSettings settings = LauncherSettings.load(root);
            LauncherLog log = new LauncherLog(root);
            Path currentJar = settings.activeJar(root);
            if (!Files.isRegularFile(currentJar)) {
                throw new IOException("현재 서버 JAR를 찾을 수 없습니다: " + currentJar);
            }

            requireJava17OrLater(settings);
            log.info("무료급식 출석 launcher " + LAUNCHER_VERSION + " 시작 (현재 서버 "
                    + settings.activeVersion() + ")");
            UpdateService updateService = new UpdateService();
            Optional<UpdateService.DownloadedUpdate> update = updateService.downloadIfNewer(root, settings, log);

            if (update.isPresent()) {
                UpdateService.DownloadedUpdate candidate = update.get();
                Process process = startServer(root, settings, candidate.jar(), log);
                if (waitForHealthyServer(process, settings, candidate.version(), log)) {
                    settings.saveActiveVersion(root, candidate.version(), candidate.jar());
                    log.info("새 버전 " + candidate.version() + " 기동 확인 완료");
                    waitForExit(process, log);
                    return;
                }

                log.info("새 버전 기동 확인 실패. 기존 버전 " + settings.activeVersion() + "으로 롤백합니다.");
                stop(process);
                settings.markFailedVersion(candidate.version());
            }

            Process process = startServer(root, settings, currentJar, log);
            waitForExit(process, log);
        } catch (Exception e) {
            System.err.println("launcher 시작 실패: " + messageOf(e));
            System.exit(1);
        }
    }

    private static Path installationRoot(String[] args) {
        if (args.length == 2 && "--root".equals(args[0])) {
            return Path.of(args[1]).toAbsolutePath().normalize();
        }
        if (args.length != 0) throw new IllegalArgumentException("지원하지 않는 launcher 인자입니다.");
        return Path.of("").toAbsolutePath().normalize();
    }

    private static Process startServer(Path root, LauncherSettings settings, Path jar, LauncherLog log)
            throws IOException {
        log.info("서버 JAR 실행: " + jar.getFileName());
        ProcessBuilder processBuilder = new ProcessBuilder(List.of(settings.optional("java.command", "java"), "-jar", jar.toString()));
        processBuilder.directory(root.toFile());
        processBuilder.inheritIO();
        Process process = processBuilder.start();
        ACTIVE_SERVER_PROCESS.set(process);
        return process;
    }

    /** 서버 JAR는 Java 17 이상에서만 실행하도록 시작 전에 명확한 오류를 낸다. */
    private static void requireJava17OrLater(LauncherSettings settings) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(settings.optional("java.command", "java"), "-version")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IOException("Java 실행 확인에 실패했습니다: " + output.strip());
        Matcher matcher = JAVA_VERSION_PATTERN.matcher(output);
        if (!matcher.find()) throw new IOException("Java 버전을 확인할 수 없습니다: " + output.strip());
        int majorVersion = Integer.parseInt(matcher.group(1));
        if (majorVersion < 17) throw new IOException("Java 17 이상이 필요합니다. 현재 Java: " + majorVersion);
    }

    private static boolean waitForHealthyServer(Process process,
                                                LauncherSettings settings,
                                                String expectedVersion,
                                                LauncherLog log) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(
                settings.positiveInt("server.startupTimeoutSeconds", 45)));
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) return false;
            try {
                if (versionMatches(settings.required("server.healthUrl"), expectedVersion)) return true;
            } catch (IOException ignored) {
                // Spring Boot가 포트를 열기 전에는 연결이 거절되는 것이 정상이다.
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        log.info("새 서버 기동 확인 시간 초과");
        return false;
    }

    private static boolean versionMatches(String healthUrl, String expectedVersion) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(healthUrl).toURL().openConnection();
        connection.setConnectTimeout(1000);
        connection.setReadTimeout(1000);
        connection.setRequestMethod("GET");
        try {
            if (connection.getResponseCode() != 200) return false;
            String body = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return expectedVersion.equals(UpdateManifest.stringValue(body, "version", true));
        } finally {
            connection.disconnect();
        }
    }

    private static void waitForExit(Process process, LauncherLog log) throws InterruptedException {
        try {
            int exitCode = process.waitFor();
            log.info("출석 서버 프로세스가 종료되었습니다. 종료 코드: " + exitCode);
        } finally {
            ACTIVE_SERVER_PROCESS.compareAndSet(process, null);
        }
    }

    private static void stop(Process process) {
        if (process == null) return;
        List<ProcessHandle> processTree = processTree(process);
        terminate(processTree, false);
        try {
            process.waitFor(35, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (processTree.stream().anyMatch(ProcessHandle::isAlive)) {
            terminate(processTree, true);
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        ACTIVE_SERVER_PROCESS.compareAndSet(process, null);
    }

    /** launcher가 끝날 때 자신이 시작한 서버와 하위 프로세스를 남기지 않는다. */
    private static void stopActiveServer() {
        stop(ACTIVE_SERVER_PROCESS.get());
    }

    private static List<ProcessHandle> processTree(Process process) {
        ProcessHandle root = process.toHandle();
        List<ProcessHandle> handles = new ArrayList<>(root.descendants().toList());
        handles.add(root);
        return handles;
    }

    private static void terminate(List<ProcessHandle> processes, boolean forcibly) {
        for (ProcessHandle process : processes) {
            if (!process.isAlive()) continue;
            if (forcibly) process.destroyForcibly();
            else process.destroy();
        }
    }

    private static String messageOf(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
