package com.wind.farm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** 指令适用机位快照。 */
@Entity
@Table(name = "grid_limit_turbine")
@Getter
@Setter
public class GridLimitTurbine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "turbine_id", nullable = false)
    private Long turbineId;

    @Column(name = "unit_code", length = 32)
    private String unitCode;

    @Column(name = "selected", nullable = false)
    private Boolean selected;

    /** SELECTED 待分摊 / ALLOCATED 已分摊 / EXCLUDED 执行前不可运行不分摊 */
    @Column(name = "allocation_state", nullable = false, length = 24)
    private String allocationState;
}
