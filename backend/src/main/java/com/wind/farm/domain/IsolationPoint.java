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
 * 隔离点：安全隔离票内的逐条隔离项，是建票时从模板深拷贝来的快照（{@link #snapshotVersion} 记录
 * 当时的模板版本），也可以是本次工单人工补充（{@link #source}=MANUAL）。
 *
 * <p>确认规则：按 {@link #pointSeq} 升序逐条隔离，每条都要双人（操作人 + 监护人，不得为同一人）；
 * 复电严格按 pointSeq 降序逐条解除，禁止跳项。确认 / 复电在 repository 层走「带状态前置条件的
 * 条件 UPDATE」，影响行数 0 即重复提交 / 已被他人处理，天然幂等，网络重试不会产生第二次推进。
 *
 * <p>{@link #validFlag}=0 表示隔离被复查判定失效（挂锁脱落、验电异常等），该点必须重新双人确认，
 * 且票在存在失效点时不能保持 / 进入 ISOLATED。
 */
@Entity
@Table(name = "isolation_point")
@Getter
@Setter
public class IsolationPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    /** 隔离顺序（从 1 开始）；复电按它的逆序 */
    @Column(name = "point_seq", nullable = false)
    private Integer pointSeq;

    @Column(name = "point_name", nullable = false, length = 64)
    private String pointName;

    /** SHUTDOWN / POWER_OFF / VOLTAGE_TEST / LOCK_OUT / MECH_LOCK */
    @Column(name = "point_type", length = 24)
    private String pointType;

    @Column(name = "standard", length = 128)
    private String standard;

    /** 来源：TEMPLATE 模板快照 / MANUAL 本次工单人工补充 */
    @Column(name = "source", length = 16)
    private String source;

    /** 模板来源点的 id（差异检查靠它找到模板最新版本）；人工补充点为 null */
    @Column(name = "template_id")
    private Long templateId;

    /** 快照时对应的模板点版本；人工补充点为 0 */
    @Column(name = "snapshot_version")
    private Integer snapshotVersion;

    /** 是否已完成隔离确认 */
    @Column(name = "isolated")
    private Boolean isolated;

    /** 隔离操作人 */
    @Column(name = "isolate_operator", length = 32)
    private String isolateOperator;

    /** 隔离监护人（双人确认，与操作人不得相同） */
    @Column(name = "isolate_verifier", length = 32)
    private String isolateVerifier;

    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "isolate_time")
    private ZonedDateTime isolateTime;

    @Column(name = "isolate_time_tz", length = 32)
    private String isolateZone;

    /** 隔离是否仍有效：0 表示复查失效，必须重新确认 */
    @Column(name = "valid_flag")
    private Boolean validFlag;

    /** 是否已复电解除 */
    @Column(name = "restored")
    private Boolean restored;

    /** 复电确认人 */
    @Column(name = "restore_operator", length = 32)
    private String restoreOperator;

    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "restore_time")
    private ZonedDateTime restoreTime;

    @Column(name = "restore_time_tz", length = 32)
    private String restoreZone;

    @Column(name = "note", length = 128)
    private String note;
}
