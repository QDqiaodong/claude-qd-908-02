package com.wind.farm.domain;

import com.wind.farm.common.Dict;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * 机位（风机）：风场下的单机单元，机位编号在同一风场内唯一。
 * 状态流转 并网 → 检修 / 故障 / 停运 → 并网，故障机位不允许登记发电读数。
 */
@Entity
@Table(name = "turbine")
@Getter
@Setter
public class Turbine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属风场 id */
    @Column(name = "farm_id", nullable = false)
    private Long farmId;

    /** 机位编号：同一风场内唯一，如 A01 */
    @Column(name = "unit_code", nullable = false, length = 32)
    private String unitCode;

    /** 机位名称，如 1 号机位 */
    @Column(name = "unit_name", length = 64)
    private String unitName;

    /** 机型，如 金风 GW155-4.5 */
    @Column(name = "model", length = 48)
    private String model;

    /** 额定功率（kW），必须 > 0 */
    @Column(name = "rated_power_kw", nullable = false, precision = 10, scale = 2)
    private BigDecimal ratedPowerKw;

    /** 轮毂高度（m） */
    @Column(name = "hub_height_m", precision = 8, scale = 2)
    private BigDecimal hubHeightM;

    /** 风轮直径（m） */
    @Column(name = "rotor_diameter_m", precision = 8, scale = 2)
    private BigDecimal rotorDiameterM;

    /** 吊装日期 */
    @Column(name = "install_date")
    private LocalDate installDate;

    /** 状态：ONLINE 并网 / MAINTAIN 检修 / FAULT 故障 / STOPPED 停运 */
    @Column(name = "status", length = 16)
    private String status;

    /** 最近一次登记的有功功率（kW） */
    @Column(name = "last_power_kw", precision = 10, scale = 2)
    private BigDecimal lastPowerKw;

    /** 今日发电量（kWh） */
    @Column(name = "today_energy_kwh", precision = 12, scale = 2)
    private BigDecimal todayEnergyKwh;

    /** 累计发电量（kWh） */
    @Column(name = "total_energy_kwh", precision = 14, scale = 2)
    private BigDecimal totalEnergyKwh;

    /** 累计运行小时（h） */
    @Column(name = "total_hours", precision = 12, scale = 1)
    private BigDecimal totalHours;

    @Column(name = "note", length = 128)
    private String note;

    @Transient
    public String getStatusName() {
        return Dict.turbineStatusName(status);
    }

    @Transient
    public String getStatusColor() {
        return Dict.turbineStatusColor(status);
    }

    /** 故障机位不允许登记发电读数 */
    @Transient
    public boolean isFault() {
        return "FAULT".equals(status);
    }

    /** 当前负荷率 = 最近有功功率 / 额定功率 */
    @Transient
    public double getLoadRate() {
        if (ratedPowerKw == null || ratedPowerKw.compareTo(BigDecimal.ZERO) <= 0 || lastPowerKw == null) {
            return 0D;
        }
        return Math.min(1D, lastPowerKw.doubleValue() / ratedPowerKw.doubleValue());
    }
}
