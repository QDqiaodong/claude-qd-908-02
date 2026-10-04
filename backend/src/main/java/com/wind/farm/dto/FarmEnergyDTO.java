package com.wind.farm.dto;

import java.math.BigDecimal;
import lombok.Data;

/**
 * 按风场口径的发电统计。
 * 注意：JPQL `select new XxxDTO(...)` 必须有参数个数完全匹配的显式构造器，
 * 只靠 Lombok @AllArgsConstructor 会报 Missing constructor，所以这里手写构造器。
 */
@Data
public class FarmEnergyDTO {

    private Long farmId;
    private String farmCode;
    private String farmName;
    private String timeZone;
    private String timeZoneName;
    private String offsetText;
    private Integer turbineCount;
    private Integer onlineCount;
    private BigDecimal ratedCapacityKw;
    private BigDecimal dailyEnergyKwh;
    private BigDecimal monthlyEnergyKwh;
    private BigDecimal totalEnergyKwh;
    private BigDecimal equivalentHours;
    private Long logCount;

    public FarmEnergyDTO() {
    }

    public FarmEnergyDTO(Long farmId, String farmCode, String farmName, String timeZone, String timeZoneName,
            String offsetText, Integer turbineCount, Integer onlineCount, BigDecimal ratedCapacityKw,
            BigDecimal dailyEnergyKwh, BigDecimal monthlyEnergyKwh, BigDecimal totalEnergyKwh,
            BigDecimal equivalentHours, Long logCount) {
        this.farmId = farmId;
        this.farmCode = farmCode;
        this.farmName = farmName;
        this.timeZone = timeZone;
        this.timeZoneName = timeZoneName;
        this.offsetText = offsetText;
        this.turbineCount = turbineCount;
        this.onlineCount = onlineCount;
        this.ratedCapacityKw = ratedCapacityKw;
        this.dailyEnergyKwh = dailyEnergyKwh;
        this.monthlyEnergyKwh = monthlyEnergyKwh;
        this.totalEnergyKwh = totalEnergyKwh;
        this.equivalentHours = equivalentHours;
        this.logCount = logCount;
    }
}
