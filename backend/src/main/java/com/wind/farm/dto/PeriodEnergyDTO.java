package com.wind.farm.dto;

import java.math.BigDecimal;
import lombok.Data;

/** 日 / 月发电量趋势 */
@Data
public class PeriodEnergyDTO {

    /** 日期 yyyy-MM-dd 或 月份 yyyy-MM */
    private String period;
    private String label;
    private BigDecimal energyKwh;
    private BigDecimal equivalentHours;
    private BigDecimal avgPowerKw;
    private Long logCount;
    private Integer turbineCount;

    public PeriodEnergyDTO() {
    }

    public PeriodEnergyDTO(String period, String label, BigDecimal energyKwh, BigDecimal equivalentHours,
            BigDecimal avgPowerKw, Long logCount, Integer turbineCount) {
        this.period = period;
        this.label = label;
        this.energyKwh = energyKwh;
        this.equivalentHours = equivalentHours;
        this.avgPowerKw = avgPowerKw;
        this.logCount = logCount;
        this.turbineCount = turbineCount;
    }
}
