package com.bgrc.attendance.launcher;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 업데이트 비교에 쓰는 엄격한 major.minor.patch 버전 값이다. */
final class Version implements Comparable<Version> {
    private static final Pattern PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)$");

    private final int major;
    private final int minor;
    private final int patch;

    private Version(int major, int minor, int patch) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }

    static Version parse(String value) {
        Matcher matcher = PATTERN.matcher(Objects.requireNonNull(value, "version").trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("버전은 major.minor.patch 형식이어야 합니다: " + value);
        }
        try {
            return new Version(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("버전 숫자가 너무 큽니다: " + value, e);
        }
    }

    @Override
    public int compareTo(Version other) {
        int comparison = Integer.compare(major, other.major);
        if (comparison != 0) return comparison;
        comparison = Integer.compare(minor, other.minor);
        return comparison != 0 ? comparison : Integer.compare(patch, other.patch);
    }
}
