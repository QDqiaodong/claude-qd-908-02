package com.wind.farm.dto;

import lombok.Data;

/** 指令状态动作的操作人和原因。 */
@Data
public class GridCommandActionRequest {

    private String operator;
    private String reason;
}
