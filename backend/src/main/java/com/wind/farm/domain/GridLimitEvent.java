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
 * 限值判定事件。读数补录 / 修正后会追加新版本，旧事件只置为 SUPERSEDED，
 * 原始读数、越限量、分类与处置依据均不覆盖。
 */
@Entity
@Table(name = "grid_limit_event")
@Getter
@Setter
public class GridLimitEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "farm_id", nullable = false)
    private Long farmId;

    @Column(name = "command_id", nullable = false)
    private Long commandId;

    @Column(name = "command_no", length = 48)
    private String commandNo;

    /** TURBINE / FARM */
    @Column(name = "scope", nullable = false, length = 16)
    private String scope;

    @Column(name = "turbine_id")
    private Long turbineId;

    @Column(name = "unit_code", length = 32)
    private String unitCode;

    @Column(name = "run_log_id")
    private Long runLogId;

    /** 同一指令 + 范围 + 机位/场站 + 观测时刻的连续判定键 */
    @Column(name = "event_key", length = 96)
    private String eventKey;

    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "observed_time", nullable = false)
    private ZonedDateTime observedTime;

    @Column(name = "observed_time_tz", length = 32)
    private String observedZone;

    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "report_time")
    private ZonedDateTime reportTime;

    @Column(name = "report_time_tz", length = 32)
    private String reportZone;

    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "evaluated_time", nullable = false)
    private ZonedDateTime evaluatedTime;

    @Column(name = "evaluated_time_tz", length = 32)
    private String evaluatedZone;

    @Column(name = "report_delay_seconds")
    private Long reportDelaySeconds;

    @Column(name = "late_flag")
    private Boolean lateFlag;

    @Column(name = "active_power_kw", precision = 10, scale = 2)
    private BigDecimal activePowerKw;

    @Column(name = "limit_kw", precision = 10, scale = 2)
    private BigDecimal limitKw;

    @Column(name = "total_power_kw", precision = 10, scale = 2)
    private BigDecimal totalPowerKw;

    @Column(name = "total_limit_kw", precision = 10, scale = 2)
    private BigDecimal totalLimitKw;

    @Column(name = "overage_kw", precision = 10, scale = 2)
    private BigDecimal overageKw;

    @Column(name = "consecutive_count")
    private Integer consecutiveCount;

    /** OK / LATE_ONLY / SINGLE_EXCEED / PERSISTENT_EXCEED / CLEARED / MISSING_DATA */
    @Column(name = "classification", nullable = false, length = 24)
    private String classification;

    /** OPEN / HANDLED / REVIEWED / RELEASED / SUPERSEDED */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** READING / BACKFILL / CORRECTION / MANUAL_RECHECK / COMMAND_CHANGE / DELETE */
    @Column(name = "trigger", length = 24)
    private String trigger;

    @Column(name = "revision_no")
    private Integer revisionNo;

    @Column(name = "supersedes_event_id")
    private Long supersedesEventId;

    @Column(name = "superseded_by_event_id")
    private Long supersededByEventId;

    @Column(name = "case_id")
    private Long caseId;

    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "note", length = 512)
    private String note;

    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "created_at")
    private ZonedDateTime createdAt;
}
