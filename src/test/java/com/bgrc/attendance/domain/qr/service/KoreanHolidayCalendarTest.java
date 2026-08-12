package com.bgrc.attendance.domain.qr.service;

import com.bgrc.attendance.domain.qr.config.HolidayApiConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KoreanHolidayCalendarTest {
    @TempDir
    Path tempDirectory;

    @Test
    void receivesMonthHolidayDataFromServerAndCachesIt() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/holidays", exchange -> {
            requests.incrementAndGet();
            byte[] body = """
                    <response><header><resultCode>00</resultCode></header><body><items>
                    <item><locdate>20260817</locdate><isHoliday>Y</isHoliday></item>
                    <item><locdate>20260818</locdate><isHoliday>N</isHoliday></item>
                    </items></body></response>
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            HolidayApiConfig config = mock(HolidayApiConfig.class);
            when(config.getUrl()).thenReturn("http://localhost:" + server.getAddress().getPort() + "/holidays");
            when(config.getServiceKey()).thenReturn("test-key");
            when(config.getCacheDir()).thenReturn(tempDirectory.toString());
            KoreanHolidayCalendar calendar = new KoreanHolidayCalendar(config);

            assertThat(calendar.isHoliday(LocalDate.of(2026, 8, 17))).isTrue();
            assertThat(calendar.isHoliday(LocalDate.of(2026, 8, 18))).isFalse();
            assertThat(requests.get()).isEqualTo(1);
            assertThat(tempDirectory.resolve("korean-public-holidays-2026-08.txt")).exists();
        } finally {
            server.stop(0);
        }
    }
}
