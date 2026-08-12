package com.bgrc.attendance.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateManifestTest {
    @Test
    void parsesOnlyHttpsManifestWithSemanticVersionAndSha256() {
        UpdateManifest manifest = UpdateManifest.parse("""
                {"version":"1.2.0","minimumLauncherVersion":"1.0.0", "jarUrl":"https://bgrc.howmanycals.online/releases/attendance-1.2.0.jar", "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                """);

        assertTrue(Version.parse(manifest.version()).compareTo(Version.parse("1.1.0")) > 0);
    }

    @Test
    void acceptsOnlyTheSignedManifestBytes() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] manifest = "{\"version\":\"1.2.0\"}".getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(manifest);
        byte[] signature = signer.sign();
        String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());

        assertDoesNotThrow(() -> UpdateService.verifySignature(manifest, signature, publicKey));
        assertThrows(SecurityException.class, () -> UpdateService.verifySignature(
                "{\"version\":\"1.2.1\"}".getBytes(StandardCharsets.UTF_8), signature, publicKey));
    }

    @Test
    void comparesOnlyThreePartReleaseVersions() {
        assertTrue(Version.parse("1.10.0").compareTo(Version.parse("1.2.9")) > 0);
        assertThrows(IllegalArgumentException.class, () -> Version.parse("1.2"));
    }

    @Test
    void remembersOnlyTheVersionThatFailedToStart(@TempDir Path root) throws Exception {
        Path config = Files.createDirectories(root.resolve("config")).resolve("launcher.properties");
        Files.writeString(config, "active.version=1.2.0\nactive.jar=versions/attendance-1.2.0.jar\n");
        LauncherSettings settings = LauncherSettings.load(root);

        settings.markFailedVersion("1.2.1");
        assertTrue("1.2.1".equals(LauncherSettings.load(root).failedVersion()));

        Path activatedJar = root.resolve("versions/attendance-1.2.1.jar");
        settings.saveActiveVersion(root, "1.2.1", activatedJar);
        LauncherSettings reloaded = LauncherSettings.load(root);
        assertTrue("1.2.1".equals(reloaded.activeVersion()));
        assertTrue(reloaded.failedVersion().isEmpty());
    }
}
