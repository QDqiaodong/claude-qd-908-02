package com.wind.farm.dto;

import java.math.BigDecimal;
import lombok.Data;

/** 按机位口径的发电统计（含等效利用小时） */
@Data
public class TurbineEnergyDTO {

    private Long turbineId;
    private Long farmId;
    private String farmName;
    private String timeZoneName;
    private String unitCode;
    private String unitName;
    private String model;
    private String status;
    private String statusName;
    private BigDecimal ratedPowerKw;
    private BigDecimal dailyEnergyKwh;
    private BigDecimal monthlyEnergyKwh;
    private BigDecimal totalEnergyKwh;
    private BigDecimal avgPowerKw;
    private BigDecimal equivalentHours;
    private Long logCount;

    public TurbineEnergyDTO() {
    }

    public TurbineEnergyDTO(Long turbineId, Long farmId, String farmName, String timeZoneName, String unitCode,
            String unitName, String model, String status, String statusName, BigDecimal ratedPowerKw,
            BigDecimal dailyEnergyKwh, BigDecimal monthlyEnergyKwh, BigDecimal totalEnergyKwh,
            BigDecimal avgPowerKw, BigDecimal equivalentHours, Long logCount) {
        this.turbineId = turbineId;
        this.farmId = farmId;
        this.farmName = farmName;
        this.timeZoneName = timeZoneName;
        this.unitCode = unitCode;
        this.unitName = unitName;
        this.model = model;
        this.status = status;
        this.statusName = statusName;
        this.ratedPowerKw = ratedPowerKw;
        this.dailyEnergyKwh = dailyEnergyKwh;
        this.monthlyEnergyKwh = monthlyEnergyKwh;
        this.totalEnergyKwh = totalEnergyKwh;
        this.avgPowerKw = avgPowerKw;
        this.equivalentHours = equivalentHours;
        this.logCount = logCount;
    }
}
