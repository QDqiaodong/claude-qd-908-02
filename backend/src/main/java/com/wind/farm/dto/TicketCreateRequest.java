package com.wind.farm.dto;

import lombok.Data;

/** 建隔离票请求：一张工单一张票，隔离点按检修类型自动从模板带入快照 */
@Data
public class TicketCreateRequest {

    /** 所属工单 id（必填） */
    private Long workOrderId;

    /** 隔离责任人 / 工作负责人（必填） */
    private String owner;

    private String note;
}
