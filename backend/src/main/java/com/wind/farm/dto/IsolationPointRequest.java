package com.wind.farm.dto;

import lombok.Data;

/** 隔离点维护请求：草稿期可对票内隔离点增改；确认中只允许补充（新增），已确认点不允许删除 */
@Data
public class IsolationPointRequest {

    /** 所属票 id（新增时必填） */
    private Long ticketId;

    private Integer pointSeq;

    private String pointName;

    /** SHUTDOWN / POWER_OFF / VOLTAGE_TEST / LOCK_OUT / MECH_LOCK */
    private String pointType;

    private String standard;

    private String note;
}
