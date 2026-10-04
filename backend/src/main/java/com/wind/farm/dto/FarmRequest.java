package com.wind.farm.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;

/** 风场新建 / 更新请求 */
@Data
public class FarmRequest {

    /** 风场编号，全场唯一 */
    private String farmCode;

    /** 风场名称 */
    private String farmName;

    /** 所属区域 */
    private String region;

    /** 省份 */
    private String province;

    /** 场站本地时区，必填且必须在词典内 */
    private String timeZone;

    /** 装机容量（MW） */
    private BigDecimal installedCapacityMw;

    /** 并网日期 */
    private LocalDate gridDate;

    /** 场站负责人 */
    private String owner;

    /** ACTIVE / MAINTAIN / STOPPED */
    private String status;

    private String note;
}
