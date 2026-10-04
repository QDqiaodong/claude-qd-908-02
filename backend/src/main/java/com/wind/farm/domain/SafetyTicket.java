package com.wind.farm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.ZonedDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TimeZoneStorage;
import org.hibernate.annotations.TimeZoneStorageType;

/**
 * 安全隔离票：每张检修工单一张票（work_order_id 唯一），票状态单向推进
 * DRAFT 草稿 → CONFIRMING 确认中 → ISOLATED 已隔离 → RESTORING 复电中 → RESTORED 已恢复。
 *
 * <p>联动闸门（在 service 层强制）：未达 ISOLATED 工单不能进入作业中；未达 RESTORED 工单不能闭环、
 * 机位不能恢复并网；CONFIRMING / ISOLATED / RESTORING 期间运行日志不允许登记发电读数。
 *
 * <p>并发与幂等：{@code active_slot} 非恢复态恒为 1、RESTORED 置 NULL，配合数据库部分唯一索引
 * uk_ticket_active_turbine 兜底「同一机位至多一张尚未恢复的票」；{@link #lockVersion} 是 JPA
 * 乐观锁，两人同时操作 / 重复点击 / 网络重试只会有一次有效推进，冲突方拿到明确提示。
 */
@Entity
@Table(name = "safety_ticket")
@Getter
@Setter
public class SafetyTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 隔离票编号，全场唯一，如 SI-2026-0003 */
    @Column(name = "ticket_no", nullable = false, unique = true, length = 32)
    private String ticketNo;

    /** 所属工单 id（一张工单一张票） */
    @Column(name = "work_order_id", nullable = false)
    private Long workOrderId;

    @Column(name = "order_no", length = 32)
    private String orderNo;

    @Column(name = "turbine_id", nullable = false)
    private Long turbineId;

    @Column(name = "farm_id", nullable = false)
    private Long farmId;

    @Column(name = "unit_code", length = 32)
    private String unitCode;

    /** 检修类型快照（与工单一致，决定模板带入的初始隔离点） */
    @Column(name = "maint_type", nullable = false, length = 24)
    private String maintType;

    /** DRAFT / CONFIRMING / ISOLATED / RESTORING / RESTORED */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 活动占位：未恢复恒为 1，已恢复置 NULL；部分唯一索引保证同一机位至多一张未恢复票 */
    @Column(name = "active_slot")
    private Long activeSlot;

    /** 当前隔离责任人（工作负责人） */
    @Column(name = "owner", length = 32)
    private String owner;

    /** 建票时模板内容的版本基线 */
    @Column(name = "template_version")
    private Integer templateVersion;

    /** 责任人已处理过的模板差异指纹（模板点 id:version 拼接），未在指纹里的新增 / 变更会被重新拦截 */
    @Column(name = "template_ack_fp", length = 1024)
    private String templateAckFp;

    /** 全部隔离点确认完成、隔离生效的时刻 */
    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "isolated_time")
    private ZonedDateTime isolatedTime;

    @Column(name = "isolated_time_tz", length = 32)
    private String isolatedZone;

    /** 全部隔离点逆序解除、隔离恢复的时刻 */
    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "restored_time")
    private ZonedDateTime restoredTime;

    @Column(name = "restored_time_tz", length = 32)
    private String restoredZone;

    @Column(name = "note", length = 128)
    private String note;

    /** JPA 乐观锁：并发推进只有一次生效 */
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
