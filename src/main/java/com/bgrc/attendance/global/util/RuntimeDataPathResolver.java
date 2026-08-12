package com.bgrc.attendance.global.util;

import org.springframework.stereotype.Component;

import java.net.URISyntaxException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 개발 환경에서는 프로젝트의 data를, 배포 JAR에서는 실행 폴더의 data를 사용하도록 경로를 해석한다.
 */
@Component
public class RuntimeDataPathResolver {
    private final Path workingDirectory = Path.of("").toAbsolutePath().normalize();
    private final Path dataDirectory = findDataDirectory();

    public Path resolve(String configuredPath) {
        Path path = Path.of(configuredPath).normalize();
        if (path.isAbsolute()) return path;
        if (path.getNameCount() > 0 && "data".equals(path.getName(0).toString())) {
            return path.getNameCount() == 1
                    ? dataDirectory
                    : dataDirectory.resolve(path.subpath(1, path.getNameCount())).normalize();
        }
        return workingDirectory.resolve(path).normalize();
    }

    /** 이전 버전이 현재 실행 폴더 아래에 남긴 data를 한 번 이어받을 때 사용한다. */
    public Path resolveFromWorkingDirectory(String configuredPath) {
        Path path = Path.of(configuredPath).normalize();
        return path.isAbsolute() ? path : workingDirectory.resolve(path).normalize();
    }

    private Path findDataDirectory() {
        Path workingProjectDataDirectory = findGradleProjectDataDirectory(workingDirectory);
        if (workingProjectDataDirectory != null) return workingProjectDataDirectory;

        Path codeSourcePath = getCodeSourcePath();
        Path codeSourceProjectDataDirectory = findGradleProjectDataDirectory(codeSourcePath);
        if (codeSourceProjectDataDirectory != null) return codeSourceProjectDataDirectory;

        // Gradle의 build/classes 또는 build/libs 아래에서 실행할 때는 항상 프로젝트 루트 data를 우선한다.
        for (Path current = codeSourcePath; current != null; current = current.getParent()) {
            if (!"build".equals(String.valueOf(current.getFileName()))) continue;
            Path projectDirectory = current.getParent();
            if (projectDirectory == null) break;
            Path projectDataDirectory = projectDirectory.resolve("data");
            if (Files.isDirectory(projectDataDirectory)) {
                return projectDataDirectory.toAbsolutePath().normalize();
            }
        }

        for (Path current = codeSourcePath; current != null; current = current.getParent()) {
            Path candidate = current.resolve("data");
            if (Files.isDirectory(candidate)) return candidate.toAbsolutePath().normalize();
        }
        return workingDirectory.resolve("data").normalize();
    }

    private Path findGradleProjectDataDirectory(Path start) {
        for (Path current = start; current != null; current = current.getParent()) {
            if (!"libs".equals(String.valueOf(current.getFileName()))) continue;
            Path buildDirectory = current.getParent();
            if (buildDirectory == null || !"build".equals(String.valueOf(buildDirectory.getFileName()))) continue;
            Path projectDirectory = buildDirectory.getParent();
            if (projectDirectory == null) return null;
            Path projectDataDirectory = projectDirectory.resolve("data");
            return Files.isDirectory(projectDataDirectory)
                    ? projectDataDirectory.toAbsolutePath().normalize()
                    : null;
        }
        return null;
    }

    private Path getCodeSourcePath() {
        try {
            URI locationUri = RuntimeDataPathResolver.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI();
            // Spring Boot 실행 JAR 내부 클래스는 jar:nested: URI를 반환한다.
            // 이는 운영체제 파일 경로가 아니므로, BAT가 설정한 실행 폴더를 기준으로 사용한다.
            if (!"file".equalsIgnoreCase(locationUri.getScheme())) return workingDirectory;

            Path location = Path.of(locationUri).toAbsolutePath().normalize();
            return Files.isRegularFile(location) ? location.getParent() : location;
        } catch (URISyntaxException | IllegalArgumentException | NullPointerException ignored) {
            return workingDirectory;
        }
    }
}
