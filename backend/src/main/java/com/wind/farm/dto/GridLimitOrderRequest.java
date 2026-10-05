package com.wind.farm.dto;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;
import lombok.Data;

@Data
public class GridLimitOrderRequest {
    private String orderNo;
    private Long farmId;
    private BigDecimal targetLimitKw;
    private String targetScope;
    private String allocationMode;
    private Integer priority;
    private String overlapMode;
    private ZonedDateTime startTime;
    private ZonedDateTime endTime;
    private String releaseCondition;
    private String dutyOperator;
    private String note;
    private List<Long> turbineIds;
    /** MANUAL 模式逐台确认的上限，key=机位 id */
    private List<ManualLimitItem> manualLimits;

    @Data
    public static class ManualLimitItem {
        private Long turbineId;
        private BigDecimal limitKw;
    }
}
