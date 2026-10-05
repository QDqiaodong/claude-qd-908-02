package com.wind.farm.dto;

import lombok.Data;

/** 偏差复核登记；补录 / 修正后的新结论在事件版本中体现。 */
@Data
public class GridCaseReviewRequest {

    /** CONFIRMED 确认偏差 / DATA_CORRECTED 读数已修正 / ESCALATE 升级处置 */
    private String result;
    private String operator;
    private String note;
}
