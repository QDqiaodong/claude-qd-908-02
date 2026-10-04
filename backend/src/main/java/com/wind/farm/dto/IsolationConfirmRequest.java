package com.wind.farm.dto;

import lombok.Data;

/** 隔离点双人确认请求（隔离确认 / 复电确认共用，复电时 verifier 可空） */
@Data
public class IsolationConfirmRequest {

    /** 操作人（必填） */
    private String operator;

    /** 监护人（隔离确认必填，且不能与操作人为同一人；复电不使用） */
    private String verifier;

    private String note;
}
