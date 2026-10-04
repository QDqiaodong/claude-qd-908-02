package com.wind.farm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 安全隔离点模板：按检修类型（maint_type）维护的标准隔离点。
 *
 * <p>设计取舍：模板只在「建票那一刻」被深拷贝成票内快照（见 SafetyTicketService#create），
 * 之后模板的任何增删改都不影响已创建的票——历史确认内容因此天然不可变、可追溯。
 * {@link #version} 是该模板点的版本号，任意内容修改 +1，差异检查用它把「建票后模板又变了」
 * 的新增 / 变更风险显式暴露给责任人，新增风险不会被旧快照静默掩盖。
 */
@Entity
@Table(name = "isolation_template")
@Getter
@Setter
public class IsolationTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 适用检修类型：ROUTINE / TEMPORARY / EMERGENCY / RETROFIT / OVERHAUL */
    @Column(name = "maint_type", nullable = false, length = 24)
    private String maintType;

    /** 同一检修类型内的隔离顺序，从 1 开始；复电严格按它的逆序 */
    @Column(name = "point_seq", nullable = false)
    private Integer pointSeq;

    /** 隔离点名称，如「逐相验电」 */
    @Column(name = "point_name", nullable = false, length = 64)
    private String pointName;

    /** 隔离手段：SHUTDOWN 停机 / POWER_OFF 停电 / VOLTAGE_TEST 验电 / LOCK_OUT 挂牌上锁 / MECH_LOCK 机械锁定 */
    @Column(name = "point_type", length = 24)
    private String pointType;

    /** 隔离标准（验收口径） */
    @Column(name = "standard", length = 128)
    private String standard;

    /** 是否启用：停用的模板点不带入新票，也不影响旧票 */
    @Column(name = "enabled")
    private Boolean enabled;

    /** 模板点版本：内容每次修改 +1 */
    @Column(name = "version")
    private Integer version;

    @Column(name = "note", length = 128)
    private String note;
}
