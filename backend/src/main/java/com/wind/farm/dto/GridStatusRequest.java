package com.wind.farm.dto;

import lombok.Data;

@Data
public class GridStatusRequest {
    private String operator;
    private String note;
    /** 结束指令时登记解除结果 */
    private String releaseResult;
}
