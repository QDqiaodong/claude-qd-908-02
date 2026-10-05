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

/** 电网限功率指令：登记、分摊、执行、暂停、结束 / 取消的主表。 */
@Entity
@Table(name = "grid_limit_order")
@Getter
@Setter
public class GridLimitOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, length = 32, unique = true)
    private String orderNo;

    @Column(name = "farm_id", nullable = false)
    private Long farmId;

    /** 场站目标有功上限（kW） */
    @Column(name = "target_limit_kw", nullable = false, precision = 10, scale = 2)
    private BigDecimal targetLimitKw;

    /** FULL 全场站 / PART 指定机位 */
    @Column(name = "target_scope", nullable = false, length = 16)
    private String targetScope;

    /** PROPORTIONAL 按额定容量比例分摊 / MANUAL 值班人员逐台确认 */
    @Column(name = "allocation_mode", nullable = false, length = 26)
    private String allocationMode;

    /** 数值越小优先级越高 */
    @Column(name = "priority", nullable = false)
    private Integer priority;

    /** COEXIST 并行取严 / SUPERSEDE 显式替代旧令 */
    @Column(name = "overlap_mode", nullable = false, length = 16)
    private String overlapMode;

    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "start_time", nullable = false)
    private ZonedDateTime startTime;

    @Column(name = "start_time_tz", length = 32)
    private String startZone;

    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "end_time", nullable = false)
    private ZonedDateTime endTime;

    @Column(name = "end_time_tz", length = 32)
    private String endZone;

    @Column(name = "release_condition", length = 256)
    private String releaseCondition;

    /** PLANNED / EXECUTING / PAUSED / ENDED / CANCELLED */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "duty_operator", length = 32)
    private String dutyOperator;

    @Column(name = "confirmed_by", length = 32)
    private String confirmedBy;

    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "activated_at")
    private ZonedDateTime activatedAt;

    @Column(name = "activated_at_tz", length = 32)
    private String activatedZone;

    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "paused_at")
    private ZonedDateTime pausedAt;

    @Column(name = "paused_at_tz", length = 32)
    private String pausedZone;

    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "resumed_at")
    private ZonedDateTime resumedAt;

    @Column(name = "resumed_at_tz", length = 32)
    private String resumedZone;

    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "ended_at")
    private ZonedDateTime endedAt;

    @Column(name = "ended_at_tz", length = 32)
    private String endedZone;

    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "cancelled_at")
    private ZonedDateTime cancelledAt;

    @Column(name = "cancelled_at_tz", length = 32)
    private String cancelledZone;

    @Column(name = "release_result", length = 256)
    private String releaseResult;

    @Column(name = "release_operator", length = 32)
    private String releaseOperator;

    @Column(name = "supersedes_order_id")
    private Long supersedesOrderId;

    @Column(name = "note", length = 256)
    private String note;

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
