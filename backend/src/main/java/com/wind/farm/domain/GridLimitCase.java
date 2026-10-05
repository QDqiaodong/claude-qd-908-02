package com.wind.farm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TimeZoneStorage;
import org.hibernate.annotations.TimeZoneStorageType;

/** 限值偏差处置单：处置 → 复核 → 解除；读数修正可追加复核事件，但不改写原处置轨迹。 */
@Entity
@Table(name = "grid_limit_case")
@Getter
@Setter
public class GridLimitCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "farm_id", nullable = false)
    private Long farmId;

    @Column(name = "command_id", nullable = false)
    private Long commandId;

    @Column(name = "initial_event_id", nullable = false)
    private Long initialEventId;

    @Column(name = "latest_event_id", nullable = false)
    private Long latestEventId;

    @Column(name = "scope", nullable = false, length = 16)
    private String scope;

    @Column(name = "turbine_id")
    private Long turbineId;

    @Column(name = "unit_code", length = 32)
    private String unitCode;

    @Column(name = "run_log_id")
    private Long runLogId;

    @Column(name = "classification", nullable = false, length = 24)
    private String classification;

    /** OPEN / HANDLED / REVIEWED / RELEASED */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "observed_time")
    private ZonedDateTime observedTime;

    @Column(name = "observed_time_tz", length = 32)
    private String observedZone;

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

    @Column(name = "handle_action", length = 256)
    private String handleAction;

    @Column(name = "handle_operator", length = 32)
    private String handleOperator;

    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "handled_time")
    private ZonedDateTime handledTime;

    @Column(name = "handled_time_tz", length = 32)
    private String handledZone;

    @Column(name = "handle_note", length = 512)
    private String handleNote;

    /** CONFIRMED / DATA_CORRECTED / ESCALATE */
    @Column(name = "review_result", length = 24)
    private String reviewResult;

    @Column(name = "review_operator", length = 32)
    private String reviewOperator;

    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "reviewed_time")
    private ZonedDateTime reviewedTime;

    @Column(name = "reviewed_time_tz", length = 32)
    private String reviewedZone;

    @Column(name = "review_note", length = 512)
    private String reviewNote;

    /** RESOLVED / COMMAND_LIFTED / MAINTAINED */
    @Column(name = "release_result", length = 24)
    private String releaseResult;

    @Column(name = "release_operator", length = 32)
    private String releaseOperator;

    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "released_time")
    private ZonedDateTime releasedTime;

    @Column(name = "released_time_tz", length = 32)
    private String releasedZone;

    @Column(name = "release_note", length = 512)
    private String releaseNote;

    @Version
    @Column(name = "lock_version")
    private Integer lockVersion;

    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "created_at")
    private ZonedDateTime createdAt;

    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "updated_at")
    private ZonedDateTime updatedAt;
}
