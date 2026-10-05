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

/** 电网限功率指令的机位分摊结果。 */
@Entity
@Table(name = "grid_limit_allocation")
@Getter
@Setter
public class GridLimitAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "turbine_id", nullable = false)
    private Long turbineId;

    @Column(name = "farm_id", nullable = false)
    private Long farmId;

    @Column(name = "unit_code", length = 32)
    private String unitCode;

    @Column(name = "rated_power_kw", nullable = false, precision = 10, scale = 2)
    private BigDecimal ratedPowerKw;

    @Column(name = "limit_kw", nullable = false, precision = 10, scale = 2)
    private BigDecimal limitKw;

    @Column(name = "runnable", nullable = false)
    private Boolean runnable;

    @Column(name = "turbine_status", length = 16)
    private String turbineStatus;

    /** ALLOCATED 已确认分摊 / EXCLUDED 不可运行不占额度 */
    @Column(name = "allocation_state", nullable = false, length = 24)
    private String allocationState;

    @Column(name = "confirm_operator", length = 32)
    private String confirmOperator;

    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "confirmed_at")
    private ZonedDateTime confirmedAt;

    @Column(name = "confirmed_at_tz", length = 32)
    private String confirmedZone;

    @Column(name = "note", length = 256)
    private String note;
}
