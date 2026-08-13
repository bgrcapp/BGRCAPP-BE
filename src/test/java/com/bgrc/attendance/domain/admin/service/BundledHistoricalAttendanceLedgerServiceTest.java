package com.bgrc.attendance.domain.admin.service;

import com.bgrc.attendance.domain.qr.config.AttendanceLogConfig;
import com.bgrc.attendance.global.util.RuntimeDataPathResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BundledHistoricalAttendanceLedgerServiceTest {
    @TempDir
    Path tempDirectory;

    @Test
    void restoresAllBundledHistoricalLedgersWithoutReplacingExistingFiles() throws Exception {
        BundledHistoricalAttendanceLedgerService service = new BundledHistoricalAttendanceLedgerService(
                config(), new RuntimeDataPathResolver());

        service.restoreMissingHistoricalLedgers();

        for (int month = 1; month <= 7; month++) {
            assertThat(Files.isRegularFile(ledgerPath(month))).isTrue();
        }

        Path january = ledgerPath(1);
        Files.writeString(january, "운영 파일은 유지한다", StandardCharsets.UTF_8);
        service.restoreMissingHistoricalLedgers();

        assertThat(Files.readString(january, StandardCharsets.UTF_8)).isEqualTo("운영 파일은 유지한다");
    }

    @Test
    void recognizesExistingLedgerEvenWhenTheKoreanSuffixIsDecomposed() throws Exception {
        Path decomposedJanuary = tempDirectory.resolve("무료급식 일일 식사내역_26.1_일지.xlsx");
        Files.writeString(decomposedJanuary, "기존 1월 일지", StandardCharsets.UTF_8);

        new BundledHistoricalAttendanceLedgerService(config(), new RuntimeDataPathResolver())
                .restoreMissingHistoricalLedgers();

        assertThat(Files.readString(decomposedJanuary, StandardCharsets.UTF_8)).isEqualTo("기존 1월 일지");
        try (Stream<Path> files = Files.list(tempDirectory)) {
            assertThat(files.filter(Files::isRegularFile)).hasSize(7);
        }
    }

    private AttendanceLogConfig config() {
        AttendanceLogConfig config = mock(AttendanceLogConfig.class);
        when(config.getMonthlyDir()).thenReturn(tempDirectory.toString());
        when(config.getLogDir()).thenReturn(tempDirectory.toString());
        return config;
    }

    private Path ledgerPath(int month) {
        return tempDirectory.resolve("무료급식 일일 식사내역_26.%d_일지.xlsx".formatted(month));
    }
}
