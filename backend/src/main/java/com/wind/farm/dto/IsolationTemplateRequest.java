package com.wind.farm.dto;

import lombok.Data;

/** 隔离点模板维护请求（按检修类型维护标准隔离点） */
@Data
public class IsolationTemplateRequest {

    private String maintType;

    private Integer pointSeq;

    private String pointName;

    private String pointType;

    private String standard;

    private Boolean enabled;

    private String note;
}
