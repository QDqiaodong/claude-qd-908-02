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
 * 隔离票变更流水：票的创建、状态推进、每个隔离点的确认 / 失效 / 重新确认 / 复电解除、
 * 模板新增风险的补入 / 留痕忽略全部在此追加记录，只增不改。
 * 操作人员从工单页面与机位页面看到的「完整变更记录」就是本表按时间正序的内容。
 */
@Entity
@Table(name = "isolation_log")
@Getter
@Setter
public class IsolationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    /** 关联隔离点（票级动作时为空） */
    @Column(name = "point_id")
    private Long pointId;

    @Column(name = "work_order_id")
    private Long workOrderId;

    @Column(name = "turbine_id")
    private Long turbineId;

    /**
     * 动作：CREATE 建票 / START_CONFIRM 开始确认 / ISOLATE_POINT 隔离点确认 /
     * POINT_INVALID 隔离失效 / RE_CONFIRM 重新确认 / ISOLATED 隔离生效 /
     * START_RESTORE 开始复电 / RESTORE_POINT 复电解除 / RESTORED 恢复完成 /
     * ADD_POINT 补充隔离点 / TPL_IMPORT 模板新增点补入 / TPL_ACK 模板差异留痕忽略
     */
    @Column(name = "action", nullable = false, length = 32)
    private String action;

    @Column(name = "from_status", length = 16)
    private String fromStatus;

    @Column(name = "to_status", length = 16)
    private String toStatus;

    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "detail", length = 512)
    private String detail;

    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "created_at")
    private ZonedDateTime createdAt;

    @Column(name = "created_at_tz", length = 32)
    private String createdZone;
}
