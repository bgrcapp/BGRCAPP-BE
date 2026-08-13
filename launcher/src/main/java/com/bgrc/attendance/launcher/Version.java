package com.bgrc.attendance.launcher;

import java.util.Objects;
import java.util.regex.Pattern;

/** 업데이트 비교에 쓰는 숫자 버전 값이다. 최소 major.minor.patch를 요구하고, 이후 숫자 단위도 허용한다. */
final class Version implements Comparable<Version> {
    private static final Pattern PATTERN = Pattern.compile("^\\d+(?:\\.\\d+){2,}$");

    private final int[] components;

    private Version(int[] components) {
        this.components = components;
    }

    static Version parse(String value) {
        String normalized = Objects.requireNonNull(value, "version").trim();
        if (!PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("버전은 major.minor.patch 또는 그 이상의 숫자 형식이어야 합니다: " + value);
        }
        try {
            String[] parts = normalized.split("\\.");
            int[] components = new int[parts.length];
            for (int index = 0; index < parts.length; index++) {
                components[index] = Integer.parseInt(parts[index]);
            }
            return new Version(components);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("버전 숫자가 너무 큽니다: " + value, e);
        }
    }

    @Override
    public int compareTo(Version other) {
        int componentCount = Math.max(components.length, other.components.length);
        for (int index = 0; index < componentCount; index++) {
            int left = index < components.length ? components[index] : 0;
            int right = index < other.components.length ? other.components[index] : 0;
            int comparison = Integer.compare(left, right);
            if (comparison != 0) return comparison;
        }
        return 0;
    }
}
