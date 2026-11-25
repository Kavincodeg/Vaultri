package com.vaultri.core.report.dto;

import com.vaultri.core.report.exception.InvalidDateRangeException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

public record DateRange(Instant start, Instant end, LocalDate fromDate, LocalDate toDate) {

    public static DateRange parseAndValidate(String fromStr, String toStr) {
        LocalDate to;
        LocalDate from;

        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        if (toStr == null || toStr.isBlank()) {
            to = today;
        } else {
            to = parseIsoDate(toStr.trim());
        }

        if (fromStr == null || fromStr.isBlank()) {
            from = to.minusDays(90);
        } else {
            from = parseIsoDate(fromStr.trim());
        }

        if (from.isAfter(to)) {
            throw new InvalidDateRangeException("Invalid date range: 'from' date must be before or equal to 'to' date.");
        }

        long daysBetween = ChronoUnit.DAYS.between(from, to);
        if (daysBetween > 366) {
            throw new InvalidDateRangeException("Date range exceeds maximum allowed limit of 366 days.");
        }

        Instant start = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        return new DateRange(start, end, from, to);
    }

    private static LocalDate parseIsoDate(String input) {
        try {
            return LocalDate.parse(input);
        } catch (DateTimeParseException e1) {
            try {
                return OffsetDateTime.parse(input).atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
            } catch (DateTimeParseException e2) {
                try {
                    return Instant.parse(input).atZone(ZoneOffset.UTC).toLocalDate();
                } catch (DateTimeParseException e3) {
                    throw new InvalidDateRangeException("Invalid date format. Expected ISO-8601 date (YYYY-MM-DD).");
                }
            }
        }
    }
}
