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

/** 运行读数对应的限功率偏差、处置、复核和解除结果。 */
@Entity
@Table(name = "grid_limit_deviation")
@Getter
@Setter
public class GridLimitDeviation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "farm_id", nullable = false)
    private Long farmId;

    @Column(name = "governing_order_id")
    private Long governingOrderId;

    @Column(name = "order_no", length = 32)
    private String orderNo;

    @Column(name = "run_log_id")
    private Long runLogId;

    @Column(name = "turbine_id")
    private Long turbineId;

    @Column(name = "unit_code", length = 32)
    private String unitCode;

    /** TURBINE 单机位 / FARM 全场 */
    @Column(name = "scope", nullable = false, length = 16)
    private String scope;

    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "record_time")
    private ZonedDateTime recordTime;

    @Column(name = "record_time_tz", length = 32)
    private String recordZone;

    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "report_time")
    private ZonedDateTime reportTime;

    @Column(name = "report_time_tz", length = 32)
    private String reportZone;

    @Column(name = "actual_kw", precision = 12, scale = 2)
    private BigDecimal actualKw;

    @Column(name = "limit_kw", precision = 10, scale = 2)
    private BigDecimal limitKw;

    @Column(name = "exceeded_kw", precision = 12, scale = 2)
    private BigDecimal exceededKw;

    /** LATE_REPORT 迟报 / FLUCTUATION 短时波动 / PERSISTENT 持续偏差 */
    @Column(name = "deviation_type", nullable = false, length = 24)
    private String deviationType;

    /** OPEN 待处置 / HANDLED 已处置 / REVIEWED 已复核 / RELEASED 已解除 / SUPERSEDED 已被新版本替代 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "revision_no", nullable = false)
    private Integer revisionNo;

    @Column(name = "latest_flag", nullable = false)
    private Boolean latestFlag;

    @Column(name = "supersedes_deviation_id")
    private Long supersedesDeviationId;

    @Column(name = "revision_reason", length = 64)
    private String revisionReason;

    @Column(name = "original_deviation_type", length = 24)
    private String originalDeviationType;

    @Column(name = "original_actual_kw", precision = 12, scale = 2)
    private BigDecimal originalActualKw;

    @Column(name = "original_limit_kw", precision = 10, scale = 2)
    private BigDecimal originalLimitKw;

    @Column(name = "handle_note", length = 512)
    private String handleNote;
    @Column(name = "review_note", length = 512)
    private String reviewNote;
    @Column(name = "release_note", length = 512)
    private String releaseNote;
    @Column(name = "handler", length = 32)
    private String handler;
    @Column(name = "reviewer", length = 32)
    private String reviewer;
    @Column(name = "releaser", length = 32)
    private String releaser;

    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "handled_at")
    private ZonedDateTime handledAt;
    @Column(name = "handled_at_tz", length = 32)
    private String handledZone;
    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "reviewed_at")
    private ZonedDateTime reviewedAt;
    @Column(name = "reviewed_at_tz", length = 32)
    private String reviewedZone;
    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "released_at")
    private ZonedDateTime releasedAt;
    @Column(name = "released_at_tz", length = 32)
    private String releasedZone;

    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "created_at")
    private ZonedDateTime createdAt;
    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "updated_at")
    private ZonedDateTime updatedAt;
}
