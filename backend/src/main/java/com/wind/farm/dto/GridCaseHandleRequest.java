package com.wind.farm.dto;

import lombok.Data;

/** 偏差处置登记。 */
@Data
public class GridCaseHandleRequest {

    private String action;
    private String operator;
    private String note;
}
