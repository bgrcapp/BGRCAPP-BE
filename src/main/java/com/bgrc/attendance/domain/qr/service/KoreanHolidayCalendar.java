package com.bgrc.attendance.domain.qr.service;

import com.bgrc.attendance.domain.qr.config.HolidayApiConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 월별 일지를 처음 만들 때 한국천문연구원 특일 정보 API에서 해당 월 공휴일을 받아온다.
 * 성공 응답은 로컬 파일에 캐시해 네트워크 장애나 재시작에도 같은 월 일지를 재현할 수 있다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KoreanHolidayCalendar {
    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Map<Integer, Set<LocalDate>> FALLBACK_HOLIDAYS = Map.of(
            2026, Set.of(
                    LocalDate.of(2026, 1, 1),
                    LocalDate.of(2026, 2, 16), LocalDate.of(2026, 2, 17), LocalDate.of(2026, 2, 18),
                    LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 2),
                    LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 5),
                    LocalDate.of(2026, 5, 24), LocalDate.of(2026, 5, 25),
                    LocalDate.of(2026, 6, 3), LocalDate.of(2026, 6, 6),
                    LocalDate.of(2026, 7, 17),
                    LocalDate.of(2026, 8, 15), LocalDate.of(2026, 8, 17),
                    LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 26),
                    LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 9),
                    LocalDate.of(2026, 12, 25)));

    private final HolidayApiConfig holidayApiConfig;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<YearMonth, Set<LocalDate>> holidaysByMonth = new ConcurrentHashMap<>();

    public boolean isHoliday(LocalDate date) {
        return getHolidays(YearMonth.from(date)).contains(date);
    }

    private Set<LocalDate> getHolidays(YearMonth month) {
        return holidaysByMonth.computeIfAbsent(month, this::loadHolidays);
    }

    private Set<LocalDate> loadHolidays(YearMonth month) {
        Set<LocalDate> cached = readCache(month);
        if (cached != null) return cached;

        if (hasServiceKey()) {
            try {
                Set<LocalDate> received = requestHolidays(month);
                writeCache(month, received);
                return received;
            } catch (Exception e) {
                log.warn("{} 공휴일 API 조회 실패. 내장 보조 달력을 사용합니다.", month, e);
            }
        } else {
            log.warn("공휴일 API 인증키가 없어 {}의 내장 보조 달력을 사용합니다.", month);
        }
        return fallbackHolidays(month);
    }

    private Set<LocalDate> requestHolidays(YearMonth month) throws Exception {
        String query = "serviceKey=" + URLEncoder.encode(holidayApiConfig.getServiceKey(), StandardCharsets.UTF_8)
                + "&pageNo=1&numOfRows=100&solYear=" + month.getYear()
                + "&solMonth=" + String.format("%02d", month.getMonthValue());
        HttpRequest request = HttpRequest.newBuilder(URI.create(holidayApiConfig.getUrl() + "?" + query))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("공휴일 API HTTP 상태: " + response.statusCode());
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(response.body())));
        NodeList resultCodes = document.getElementsByTagName("resultCode");
        if (resultCodes.getLength() > 0 && !"00".equals(resultCodes.item(0).getTextContent().trim())) {
            throw new IOException("공휴일 API 응답 오류: " + resultCodes.item(0).getTextContent().trim());
        }

        Set<LocalDate> dates = ConcurrentHashMap.newKeySet();
        NodeList items = document.getElementsByTagName("item");
        for (int index = 0; index < items.getLength(); index++) {
            Element item = (Element) items.item(index);
            if (!"Y".equals(elementText(item, "isHoliday"))) continue;
            String dateText = elementText(item, "locdate");
            if (!dateText.isBlank()) dates.add(LocalDate.parse(dateText, COMPACT_DATE));
        }
        return Set.copyOf(dates);
    }

    private String elementText(Element item, String tagName) {
        NodeList elements = item.getElementsByTagName(tagName);
        return elements.getLength() == 0 ? "" : elements.item(0).getTextContent().trim();
    }

    private Set<LocalDate> readCache(YearMonth month) {
        Path cachePath = cachePath(month);
        if (!Files.exists(cachePath)) return null;
        try {
            return Files.readAllLines(cachePath).stream()
                    .filter(value -> !value.isBlank())
                    .map(LocalDate::parse)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        } catch (Exception e) {
            log.warn("공휴일 캐시 읽기 실패: {}", cachePath, e);
            return null;
        }
    }

    private void writeCache(YearMonth month, Set<LocalDate> dates) {
        Path cachePath = cachePath(month);
        try {
            Files.createDirectories(cachePath.getParent());
            Files.write(cachePath, dates.stream().sorted().map(LocalDate::toString).toList());
        } catch (IOException e) {
            log.warn("공휴일 캐시 저장 실패: {}", cachePath, e);
        }
    }

    private Path cachePath(YearMonth month) {
        return Path.of(holidayApiConfig.getCacheDir())
                .resolve("korean-public-holidays-%s.txt".formatted(month));
    }

    private boolean hasServiceKey() {
        return holidayApiConfig.getServiceKey() != null && !holidayApiConfig.getServiceKey().isBlank();
    }

    private Set<LocalDate> fallbackHolidays(YearMonth month) {
        Set<LocalDate> dates = FALLBACK_HOLIDAYS.getOrDefault(month.getYear(), Set.of());
        return dates.stream().filter(date -> YearMonth.from(date).equals(month)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
