package com.bgrc.attendance.launcher;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/** 서버 시작 시 1회만 manifest를 확인하고, 서명과 해시가 맞는 JAR만 내려받는다. */
final class UpdateService {
    record DownloadedUpdate(String version, Path jar) {}

    Optional<DownloadedUpdate> downloadIfNewer(Path root,
                                               LauncherSettings settings,
                                               LauncherLog log) {
        try {
            URI manifestUrl = URI.create(settings.required("update.manifestUrl"));
            if (!"https".equalsIgnoreCase(manifestUrl.getScheme())) {
                throw new IllegalArgumentException("업데이트 manifest 주소는 HTTPS여야 합니다.");
            }
            byte[] manifestBytes = download(manifestUrl,
                    settings.positiveInt("update.connectTimeoutSeconds", 3),
                    settings.positiveInt("update.readTimeoutSeconds", 5));
            byte[] signatureBytes = Base64.getDecoder().decode(new String(download(
                    URI.create(manifestUrl + ".sig"),
                    settings.positiveInt("update.connectTimeoutSeconds", 3),
                    settings.positiveInt("update.readTimeoutSeconds", 5)), StandardCharsets.US_ASCII).trim());
            verifySignature(manifestBytes, signatureBytes, settings.required("update.publicKeyBase64"));

            UpdateManifest manifest = UpdateManifest.parse(new String(manifestBytes, StandardCharsets.UTF_8));
            if (manifest.minimumLauncherVersion() != null
                    && Version.parse(AttendanceLauncher.LAUNCHER_VERSION)
                    .compareTo(Version.parse(manifest.minimumLauncherVersion())) < 0) {
                log.info("새 버전 " + manifest.version() + "은 launcher 업데이트가 필요하여 적용하지 않습니다.");
                return Optional.empty();
            }
            if (Version.parse(manifest.version()).compareTo(Version.parse(settings.activeVersion())) <= 0) {
                log.info("현재 버전 " + settings.activeVersion() + "이 최신입니다.");
                return Optional.empty();
            }
            if (manifest.version().equals(settings.failedVersion())) {
                log.info("이전 기동 확인에 실패한 버전 " + manifest.version()
                        + "은 건너뛰고 기존 버전을 실행합니다.");
                return Optional.empty();
            }

            Path versionsDirectory = root.resolve("versions").normalize();
            Files.createDirectories(versionsDirectory);
            Path target = versionsDirectory.resolve("attendance-" + manifest.version() + ".jar").normalize();
            if (!target.startsWith(versionsDirectory)) throw new IOException("안전하지 않은 업데이트 파일 경로입니다.");

            if (Files.isRegularFile(target) && manifest.sha256().equals(sha256(target))) {
                log.info("이미 검증된 새 버전 " + manifest.version() + "을 사용합니다.");
                return Optional.of(new DownloadedUpdate(manifest.version(), target));
            }

            log.info("새 버전 " + manifest.version() + " 다운로드를 시작합니다.");
            Path temporary = Files.createTempFile(versionsDirectory, ".attendance-", ".jar.part");
            try {
                downloadTo(manifest.jarUrl(), temporary,
                        settings.positiveInt("update.connectTimeoutSeconds", 3),
                        settings.positiveInt("update.downloadTimeoutSeconds", 120));
                String downloadedHash = sha256(temporary);
                if (!manifest.sha256().equals(downloadedHash)) {
                    throw new IOException("다운로드한 JAR의 SHA-256이 manifest와 일치하지 않습니다.");
                }
                moveAtomically(temporary, target);
                log.info("새 버전 " + manifest.version() + " 다운로드·검증 완료");
                return Optional.of(new DownloadedUpdate(manifest.version(), target));
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (Exception e) {
            // 업데이트 서버 장애는 출석 서버 기동 실패로 이어지면 안 된다.
            log.info("업데이트 확인을 건너뛰고 기존 버전을 실행합니다: " + safeMessage(e));
            return Optional.empty();
        }
    }

    static void verifySignature(byte[] manifest, byte[] signature, String publicKeyBase64) throws Exception {
        PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64.trim())));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(manifest);
        if (!verifier.verify(signature)) throw new SecurityException("manifest 서명 검증에 실패했습니다.");
    }

    static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream inputStream = new BufferedInputStream(Files.newInputStream(path))) {
            byte[] buffer = new byte[8192];
            for (int read; (read = inputStream.read(buffer)) >= 0; ) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static byte[] download(URI uri, int connectTimeoutSeconds, int readTimeoutSeconds) throws IOException {
        try (InputStream inputStream = open(uri, connectTimeoutSeconds, readTimeoutSeconds);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            inputStream.transferTo(outputStream);
            return outputStream.toByteArray();
        }
    }

    private static void downloadTo(URI uri, Path destination, int connectTimeoutSeconds, int readTimeoutSeconds)
            throws IOException {
        try (InputStream inputStream = open(uri, connectTimeoutSeconds, readTimeoutSeconds)) {
            Files.copy(inputStream, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static InputStream open(URI uri, int connectTimeoutSeconds, int readTimeoutSeconds) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(Math.toIntExact(connectTimeoutSeconds * 1000L));
        connection.setReadTimeout(Math.toIntExact(readTimeoutSeconds * 1000L));
        connection.setRequestMethod("GET");
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            connection.disconnect();
            throw new IOException("업데이트 서버 응답 오류 (HTTP " + status + ")");
        }
        return new DisconnectingInputStream(connection.getInputStream(), connection);
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static final class DisconnectingInputStream extends InputStream {
        private final InputStream delegate;
        private final HttpURLConnection connection;

        private DisconnectingInputStream(InputStream delegate, HttpURLConnection connection) {
            this.delegate = delegate;
            this.connection = connection;
        }

        @Override public int read() throws IOException { return delegate.read(); }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            return delegate.read(buffer, offset, length);
        }
        @Override public void close() throws IOException {
            try {
                delegate.close();
            } finally {
                connection.disconnect();
            }
        }
    }
}
