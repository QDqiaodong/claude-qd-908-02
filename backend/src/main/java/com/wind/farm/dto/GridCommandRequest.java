package com.wind.farm.dto;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;
import lombok.Data;

/** 电网限功率指令登记 / 修改请求。 */
@Data
public class GridCommandRequest {

    private String commandNo;
    private Long farmId;
    /** 目标场站 / 适用机位集合总上限，kW */
    private BigDecimal targetLimitKw;
    /** PROPORTIONAL / MANUAL */
    private String allocationMode;
    private Integer priorityRank;
    private String overlapPolicy;
    private ZonedDateTime startTime;
    private ZonedDateTime endTime;
    private String releaseCondition;
    private String owner;
    private String note;
    /** 适用机位；为空时默认整风场所有机位 */
    private List<Long> turbineIds;
}
