package com.bgrc.attendance.launcher;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.net.URI;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 개발 PC에서만 실행하는 manifest 서명 도구다.
 * 개인키는 인자 파일에서만 읽고, 출력·로그·Ubuntu 서버에는 남기지 않는다.
 */
public final class UpdateManifestTool {
    private UpdateManifestTool() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseOptions(args);
        String version = required(options, "version");
        Version.parse(version);
        Path jar = Path.of(required(options, "jar")).toAbsolutePath().normalize();
        Path privateKeyPath = Path.of(required(options, "private-key")).toAbsolutePath().normalize();
        Path releaseDirectory = Path.of(required(options, "release-directory")).toAbsolutePath().normalize();
        Path stableDirectory = Path.of(required(options, "stable-directory")).toAbsolutePath().normalize();
        String baseUrl = required(options, "base-url").replaceAll("/+$", "");
        String minimumLauncherVersion = options.getOrDefault("minimum-launcher-version", "1.0.0");
        Version.parse(minimumLauncherVersion);
        URI baseUri = URI.create(baseUrl);
        if (!"https".equalsIgnoreCase(baseUri.getScheme()) || baseUri.getHost() == null) {
            throw new IllegalArgumentException("--base-url은 HTTPS 주소여야 합니다.");
        }

        if (!Files.isRegularFile(jar)) throw new IllegalArgumentException("JAR 파일을 찾을 수 없습니다: " + jar);
        if (!Files.isRegularFile(privateKeyPath)) throw new IllegalArgumentException("개인키 파일을 찾을 수 없습니다: " + privateKeyPath);
        String jarFileName = "attendance-" + version + ".jar";
        Path releasedJar = releaseDirectory.resolve(jarFileName);
        Files.createDirectories(releaseDirectory);
        Files.copy(jar, releasedJar, StandardCopyOption.REPLACE_EXISTING);

        String manifest = "{\n"
                + "  \"version\": \"" + version + "\",\n"
                + "  \"minimumLauncherVersion\": \"" + minimumLauncherVersion + "\",\n"
                + "  \"jarUrl\": \"" + baseUri + "/releases/" + jarFileName + "\",\n"
                + "  \"sha256\": \"" + UpdateService.sha256(releasedJar) + "\",\n"
                + "  \"publishedAt\": \"" + OffsetDateTime.now() + "\"\n"
                + "}\n";
        byte[] manifestBytes = manifest.getBytes(StandardCharsets.UTF_8);
        byte[] signature = sign(manifestBytes, privateKeyPath);

        Files.createDirectories(stableDirectory);
        writeAtomically(stableDirectory.resolve("manifest.json"), manifestBytes);
        writeAtomically(stableDirectory.resolve("manifest.json.sig"),
                (Base64.getEncoder().encodeToString(signature) + "\n").getBytes(StandardCharsets.US_ASCII));
        System.out.println("서명된 업데이트 manifest 생성 완료: " + version);
        System.out.println("JAR SHA-256: " + UpdateService.sha256(releasedJar));
    }

    private static byte[] sign(byte[] content, Path privateKeyPath) throws Exception {
        String pem = Files.readString(privateKeyPath, StandardCharsets.US_ASCII)
                .replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
        PrivateKey privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(content);
        return signer.sign();
    }

    private static void writeAtomically(Path target, byte[] content) throws Exception {
        Path temporary = Files.createTempFile(target.getParent(), ".manifest-", ".tmp");
        try {
            Files.write(temporary, content);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Map<String, String> parseOptions(String[] args) {
        if (args.length % 2 != 0) throw new IllegalArgumentException("옵션은 --이름 값 형식이어야 합니다.");
        Map<String, String> options = new LinkedHashMap<>();
        for (int index = 0; index < args.length; index += 2) {
            String key = args[index];
            if (!key.startsWith("--")) throw new IllegalArgumentException("잘못된 옵션: " + key);
            options.put(key.substring(2), args[index + 1]);
        }
        return options;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("필수 옵션이 없습니다: --" + key);
        return value;
    }
}
