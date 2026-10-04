package com.wind.farm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TimeZoneStorage;
import org.hibernate.annotations.TimeZoneStorageType;

/**
 * 运行日志：按时刻登记机位的风速 / 有功功率 / 日发电量。
 *
 * <p>时间字段是 ZonedDateTime，走 {@code @TimeZoneStorage(NORMALIZE)}：Hibernate 按
 * {@code hibernate.jdbc.time_zone: Asia/Shanghai} 把时刻归一化后写进 DATETIME，读出来同样是
 * 归一化时区的 ZonedDateTime，读写只认时间列本身，不再掺时区列。
 *
 * <p>记录本身属于哪个时区落在 record_time_tz / report_time_tz 两列上，存 ZoneId 文本
 * （如 {@code Asia/Shanghai}），由 service 按所属风场的 wind_farm.time_zone 写入，
 * 展示本地时间时优先取它，取不到再退回风场时区。
 */
@Entity
@Table(name = "run_log")
@Getter
@Setter
public class RunLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "turbine_id", nullable = false)
    private Long turbineId;

    @Column(name = "farm_id", nullable = false)
    private Long farmId;

    /** 机位编号（冗余，便于列表直接展示） */
    @Column(name = "unit_code", length = 32)
    private String unitCode;

    /** 记录时刻，按 hibernate.jdbc.time_zone 归一化存储 */
    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "record_time", nullable = false)
    private ZonedDateTime recordTime;

    /** 记录时刻所属时区的 ZoneId 文本，如 Asia/Shanghai */
    @Column(name = "record_time_tz", length = 32)
    private String recordZone;

    /** 上报时刻（值班员抄录上报时间），同样按 hibernate.jdbc.time_zone 归一化存储 */
    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "report_time")
    private ZonedDateTime reportTime;

    /** 上报时刻所属时区的 ZoneId 文本 */
    @Column(name = "report_time_tz", length = 32)
    private String reportZone;

    /** 风速（m/s），不得为负 */
    @Column(name = "wind_speed_ms", nullable = false, precision = 6, scale = 2)
    private BigDecimal windSpeedMs;

    /** 有功功率（kW），不得超过额定功率 */
    @Column(name = "active_power_kw", nullable = false, precision = 10, scale = 2)
    private BigDecimal activePowerKw;

    /** 当日累计发电量（kWh），> 0 且不得超过 额定功率 × 24h */
    @Column(name = "daily_energy_kwh", nullable = false, precision = 12, scale = 2)
    private BigDecimal dailyEnergyKwh;

    /** 风向：西北 / 北 / 东北 等 */
    @Column(name = "wind_direction", length = 16)
    private String windDirection;

    /** 机舱温度（℃） */
    @Column(name = "temperature_c", precision = 6, scale = 2)
    private BigDecimal temperatureC;

    /** 值班员 */
    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "note", length = 128)
    private String note;
}
