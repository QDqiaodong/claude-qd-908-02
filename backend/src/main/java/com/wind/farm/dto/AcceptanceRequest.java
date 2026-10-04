package com.wind.farm.dto;

import lombok.Data;

/** 工单验收登记：待验收阶段由验收人填写；合格才可复电，驳回退回作业中 */
@Data
public class AcceptanceRequest {

    /** PASS 验收合格 / FAIL 验收驳回（必填） */
    private String result;

    /** 验收人（必填） */
    private String person;

    private String note;
}
