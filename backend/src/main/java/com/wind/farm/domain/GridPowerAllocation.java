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

/** 电网限功率指令的机位分摊快照和执行期当前额度。 */
@Entity
@Table(name = "grid_power_allocation")
@Getter
@Setter
public class GridPowerAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "command_id", nullable = false)
    private Long commandId;

    @Column(name = "farm_id", nullable = false)
    private Long farmId;

    @Column(name = "turbine_id", nullable = false)
    private Long turbineId;

    @Column(name = "unit_code", length = 32)
    private String unitCode;

    @Column(name = "rated_power_kw", nullable = false, precision = 10, scale = 2)
    private BigDecimal ratedPowerKw;

    /** 是否在指令适用集合内；取消勾选的机位不生成有效额度 */
    @Column(name = "included_flag", nullable = false)
    private Boolean includedFlag;

    /** 最近一次重算时是否可运行（ONLINE）；检修 / 故障 / 停运为 false，不占用额度 */
    @Column(name = "runnable_flag", nullable = false)
    private Boolean runnableFlag;

    @Column(name = "allocation_mode", nullable = false, length = 24)
    private String allocationMode;

    /** 启动前确认的分摊值；手动模式为值班人员逐台确认值 */
    @Column(name = "allocated_limit_kw", precision = 10, scale = 2)
    private BigDecimal allocatedLimitKw;

    /** 执行期实际额度；不可运行机位恒为 0 */
    @Column(name = "current_limit_kw", precision = 10, scale = 2)
    private BigDecimal currentLimitKw;

    @Column(name = "manual_confirmed", nullable = false)
    private Boolean manualConfirmed;

    /** DRAFT / CONFIRMED / EXCLUDED / RELEASED */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "reason", length = 256)
    private String reason;

    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "allocated_time")
    private ZonedDateTime allocatedTime;

    @Column(name = "allocated_time_tz", length = 32)
    private String allocatedZone;

    @Version
    @Column(name = "lock_version")
    private Integer lockVersion;
}
