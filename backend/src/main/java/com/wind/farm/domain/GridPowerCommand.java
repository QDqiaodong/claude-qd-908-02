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

/**
 * 电网限功率指令：待执行 → 执行中 ⇄ 已暂停 → 已结束，计划或执行中的指令可取消。
 * 时间全部保存为绝对时刻并另存风场 ZoneId，跨本地日期 / 时区调整时不会因日期字符串产生空档。
 */
@Entity
@Table(name = "grid_power_command")
@Getter
@Setter
public class GridPowerCommand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "command_no", nullable = false, length = 48, unique = true)
    private String commandNo;

    @Column(name = "farm_id", nullable = false)
    private Long farmId;

    /** 场站 / 适用机位集合的总目标上限（kW） */
    @Column(name = "target_limit_kw", nullable = false, precision = 10, scale = 2)
    private BigDecimal targetLimitKw;

    /** PROPORTIONAL 按可运行额定容量比例分摊 / MANUAL 值班人员逐台确认 */
    @Column(name = "allocation_mode", nullable = false, length = 24)
    private String allocationMode;

    /** PLANNED / EXECUTING / PAUSED / FINISHED / CANCELLED */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 非终态为 1，结束 / 取消置空，便于数据库识别活动指令 */
    @Column(name = "active_slot")
    private Long activeSlot;

    /** 数值越小优先级越高；调度紧急指令可优先协调，但覆盖仍取最严限值 */
    @Column(name = "priority_rank", nullable = false)
    private Integer priorityRank;

    /** 当前只支持 STRICT_MIN：同机位多条有效指令取最小值 */
    @Column(name = "overlap_policy", nullable = false, length = 24)
    private String overlapPolicy;

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

    /** NOT_CONFIRMED / PREVIEWED / CONFIRMED */
    @Column(name = "allocation_status", length = 24)
    private String allocationStatus;

    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "activated_time")
    private ZonedDateTime activatedTime;

    @Column(name = "activated_time_tz", length = 32)
    private String activatedZone;

    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "paused_time")
    private ZonedDateTime pausedTime;

    @Column(name = "paused_time_tz", length = 32)
    private String pausedZone;

    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "finished_time")
    private ZonedDateTime finishedTime;

    @Column(name = "finished_time_tz", length = 32)
    private String finishedZone;

    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "cancelled_time")
    private ZonedDateTime cancelledTime;

    @Column(name = "cancelled_time_tz", length = 32)
    private String cancelledZone;

    @Column(name = "owner", length = 32)
    private String owner;

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
