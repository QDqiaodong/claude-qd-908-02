package com.wind.farm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.ZonedDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TimeZoneStorage;
import org.hibernate.annotations.TimeZoneStorageType;

/**
 * 检修工单：工单号唯一、检修类型 5 选 1、状态按 待派 → 作业中 → 待验收 → 已闭环 推进。
 *
 * <p>计划开始 / 实际完工两个时刻是 ZonedDateTime，走 {@code @TimeZoneStorage(NORMALIZE)}，
 * 按 hibernate.jdbc.time_zone 归一化存 DATETIME；所属时区另存 plan_start_time_tz /
 * actual_finish_time_tz 两列（ZoneId 文本，由 service 按所属风场写入）。
 */
@Entity
@Table(name = "work_order")
@Getter
@Setter
public class WorkOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 工单号：全场唯一，如 WO-2026-0007 */
    @Column(name = "order_no", nullable = false, unique = true, length = 32)
    private String orderNo;

    @Column(name = "turbine_id", nullable = false)
    private Long turbineId;

    @Column(name = "farm_id", nullable = false)
    private Long farmId;

    @Column(name = "unit_code", length = 32)
    private String unitCode;

    /** 检修类型：ROUTINE 定期维护 / TEMPORARY 临时检修 / EMERGENCY 紧急抢修 / RETROFIT 技术改造 / OVERHAUL 大修 */
    @Column(name = "maint_type", nullable = false, length = 24)
    private String maintType;

    /** 工单标题 */
    @Column(name = "title", length = 64)
    private String title;

    /** 检修班组 */
    @Column(name = "crew", length = 32)
    private String crew;

    /** 计划开始时刻，按 hibernate.jdbc.time_zone 归一化存储 */
    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "plan_start_time")
    private ZonedDateTime planStartTime;

    /** 计划开始时刻所属时区的 ZoneId 文本，如 Asia/Shanghai */
    @Column(name = "plan_start_time_tz", length = 32)
    private String planZone;

    /** 实际完工时刻，按 hibernate.jdbc.time_zone 归一化存储 */
    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "actual_finish_time")
    private ZonedDateTime actualFinishTime;

    /** 实际完工时刻所属时区的 ZoneId 文本 */
    @Column(name = "actual_finish_time_tz", length = 32)
    private String finishZone;

    /** 状态：PENDING 待派 / WORKING 作业中 / CHECKING 待验收 / CLOSED 已闭环 */
    @Column(name = "status", length = 16)
    private String status;

    /** 验收结论：PASS 验收合格 / FAIL 验收驳回；为空表示尚未验收（隔离复电的前置条件） */
    @Column(name = "accept_result", length = 16)
    private String acceptResult;

    /** 验收人 */
    @Column(name = "accept_person", length = 32)
    private String acceptPerson;

    /** 验收时刻，按 hibernate.jdbc.time_zone 归一化存储 */
    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "accept_time")
    private ZonedDateTime acceptTime;

    /** 验收时刻所属时区的 ZoneId 文本 */
    @Column(name = "accept_time_tz", length = 32)
    private String acceptZone;

    /** 验收意见 */
    @Column(name = "accept_note", length = 128)
    private String acceptNote;

    /** 缺陷描述 */
    @Column(name = "fault_desc", length = 128)
    private String faultDesc;

    @Column(name = "note", length = 128)
    private String note;
}
