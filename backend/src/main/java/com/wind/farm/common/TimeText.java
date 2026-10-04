package com.wind.farm.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 时间文本工具：实体里的时间字段是 ZonedDateTime（Hibernate 时区归一化存储），
 * 出境一律在这里转成字符串，前端不再做时区换算。
 */
public final class TimeText {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private TimeText() {
    }

    /** ISO 原文，如 2026-09-28T08:00+08:00 */
    public static String iso(ZonedDateTime time) {
        return time == null ? null : time.toString();
    }

    /** 换算到指定时区后的本地时间文本 */
    public static String local(ZonedDateTime time, String zone) {
        if (time == null) {
            return null;
        }
        ZoneId target = Dict.zoneOf(zone);
        return time.withZoneSameInstant(target).format(DATE_TIME);
    }

    /** 换算到指定时区后的本地日期 yyyy-MM-dd */
    public static String localDate(ZonedDateTime time, String zone) {
        if (time == null) {
            return null;
        }
        return time.withZoneSameInstant(Dict.zoneOf(zone)).toLocalDate().format(DATE);
    }

    /** 换算到指定时区后的年月 yyyy-MM */
    public static String localMonth(ZonedDateTime time, String zone) {
        if (time == null) {
            return null;
        }
        return time.withZoneSameInstant(Dict.zoneOf(zone)).toLocalDate().toString().substring(0, 7);
    }

    /**
     * 记录自带时区优先，取不到（空 / 非法）再退回所属风场的时区。
     * 时间列统一按 hibernate.jdbc.time_zone 归一化存，展示哪个时区的墙上时间全看这两列。
     */
    public static String pickZone(String ownZone, String fallbackZone) {
        return Dict.isValidTimeZone(ownZone) ? ownZone : fallbackZone;
    }

    public static String date(LocalDate date) {
        return date == null ? null : date.format(DATE);
    }

    public static String dateTime(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.format(DATE_TIME);
    }
}
