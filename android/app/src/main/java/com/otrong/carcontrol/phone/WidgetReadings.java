package com.otrong.carcontrol.phone;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WidgetReadings {
    private static final Pattern RANGE = Pattern.compile(
            "(\\d{1,3}(?:,\\d{3})|\\d{1,5})\\s*(?:km|㎞|公里)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PERCENT = Pattern.compile("(\\d{1,3})\\s*%");
    private static final Pattern UPDATED = Pattern.compile(
            "(?:(\\d{4})[-/年])?(\\d{1,2})[-/月](\\d{1,2})(?:\\s*日\\s*|\\s+)"
                    + "(凌晨|上午|早上|中午|下午|傍晚|晚上)?\\s*(\\d{1,2}):(\\d{2})(?:\\s*(AM|PM))?",
            Pattern.CASE_INSENSITIVE);

    record Distance(String value, String unit) {
        Integer kilometers() {
            String text = value.replace('\u00a0', ' ').trim();
            Matcher distance = RANGE.matcher(text);
            String cleanedUnit = unit.replace('\u00a0', ' ').trim();
            if (!cleanedUnit.isEmpty()) {
                if (!cleanedUnit.equalsIgnoreCase("km") && !cleanedUnit.equals("㎞") && !cleanedUnit.equals("公里")) {
                    return null;
                }
                if (!distance.matches()) distance = RANGE.matcher(text + " " + cleanedUnit);
            }
            return distance.matches() ? Integer.parseInt(distance.group(1).replace(",", "")) : null;
        }

        Integer percent() {
            String text = value.replace('\u00a0', ' ').trim();
            String cleanedUnit = unit.replace('\u00a0', ' ').trim();
            Matcher match = PERCENT.matcher(cleanedUnit.isEmpty() ? text : text + " " + cleanedUnit);
            if (!match.matches()) return null;
            int pct = Integer.parseInt(match.group(1));
            return pct >= 0 && pct <= 100 ? pct : null;
        }
    }

    record Energy(Distance distance, Integer percent) {}

    private WidgetReadings() {}

    static VehicleStatus parse(Distance total, Energy electric, Energy fuel, String motion,
                               String updateLabel, long receivedAt, ZoneId zone) {
        return parse(BrandConfig.GALAXY, total, electric, fuel, motion, updateLabel, receivedAt, zone);
    }

    static VehicleStatus parse(BrandConfig config, Distance total, Energy electric, Energy fuel, String motion,
                               String updateLabel, long receivedAt, ZoneId zone) {
        String label = updateLabel.trim();
        Matcher time = UPDATED.matcher(label);
        long updatedAt = time.find() ? parseTime(time, receivedAt, zone) : 0;
        if (updatedAt == 0) return VehicleStatus.EMPTY;

        Integer totalKm = total.kilometers();
        Integer electricKm = electric.distance().kilometers();
        Integer fuelKm = fuel.distance().kilometers();
        Integer electricPercent = config == BrandConfig.ZEEKR && electric.percent() == null
                ? total.percent() : electric.percent();
        if (config == BrandConfig.GEELY && "更新于03/15 12:00".equals(label)
                && totalKm == null && electricKm == null && fuelKm == null) {
            return VehicleStatus.EMPTY;
        }
        if ("03-15 12:00".equals(label)
                && (Integer.valueOf(364).equals(totalKm)
                || (totalKm == null && Integer.valueOf(37).equals(electric.percent())
                && Integer.valueOf(37).equals(fuel.percent())))
                && electricKm == null && fuelKm == null) {
            return VehicleStatus.EMPTY;
        }
        if (totalKm == null && electricKm == null && fuelKm == null
                && electricPercent == null && fuel.percent() == null) {
            return VehicleStatus.EMPTY;
        }
        return new VehicleStatus(totalKm, electricKm, fuelKm, electricPercent, fuel.percent(),
                motion.trim(), updatedAt, label);
    }

    private static long parseTime(Matcher time, long now, ZoneId zone) {
        LocalDateTime current = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone);
        int month = Integer.parseInt(time.group(2));
        int year = time.group(1) != null ? Integer.parseInt(time.group(1))
                : current.getYear() - (current.getMonthValue() == 1 && month == 12 ? 1 : 0);
        int hour = adjustHour(Integer.parseInt(time.group(5)), time.group(4), time.group(7));
        try {
            LocalDateTime value = LocalDateTime.of(year, month, Integer.parseInt(time.group(3)),
                    hour, Integer.parseInt(time.group(6)));
            long epoch = value.atZone(zone).toInstant().toEpochMilli();
            return epoch > 0 && epoch <= now + 120_000 ? epoch : 0;
        } catch (DateTimeException invalid) {
            return 0;
        }
    }

    private static int adjustHour(int rawHour, String cnPeriod, String amPm) {
        if (cnPeriod != null) {
            return switch (cnPeriod) {
                case "凌晨", "上午", "早上" -> rawHour == 12 ? 0 : rawHour;
                case "中午" -> rawHour >= 1 && rawHour <= 10 ? rawHour + 12 : rawHour;
                case "下午", "傍晚", "晚上" -> rawHour >= 1 && rawHour <= 11 ? rawHour + 12 : rawHour;
                default -> rawHour;
            };
        }
        if ("AM".equalsIgnoreCase(amPm)) return rawHour == 12 ? 0 : rawHour;
        if ("PM".equalsIgnoreCase(amPm)) return rawHour >= 1 && rawHour <= 11 ? rawHour + 12 : rawHour;
        return rawHour;
    }
}
