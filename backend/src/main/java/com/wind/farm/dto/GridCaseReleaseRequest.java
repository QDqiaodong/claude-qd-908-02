package com.wind.farm.dto;

import lombok.Data;

/** 偏差解除结果登记。 */
@Data
public class GridCaseReleaseRequest {

    /** RESOLVED 已恢复限值内 / COMMAND_LIFTED 指令已解除 / MAINTAINED 维持跟踪 */
    private String result;
    private String operator;
    private String note;
}
