package com.wind.farm.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;

/** 机位新建 / 更新请求 */
@Data
public class TurbineRequest {

    /** 所属风场 id */
    private Long farmId;

    /** 机位编号，同一风场内唯一 */
    private String unitCode;

    /** 机位名称 */
    private String unitName;

    /** 机型 */
    private String model;

    /** 额定功率（kW），必须 > 0 */
    private BigDecimal ratedPowerKw;

    /** 轮毂高度（m） */
    private BigDecimal hubHeightM;

    /** 风轮直径（m） */
    private BigDecimal rotorDiameterM;

    /** 吊装日期 */
    private LocalDate installDate;

    /** ONLINE / MAINTAIN / FAULT / STOPPED */
    private String status;

    /** 累计发电量（kWh） */
    private BigDecimal totalEnergyKwh;

    /** 累计运行小时（h） */
    private BigDecimal totalHours;

    private String note;
}
