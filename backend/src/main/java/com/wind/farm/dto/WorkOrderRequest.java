package com.wind.farm.dto;

import java.time.ZonedDateTime;
import lombok.Data;

/** 检修工单新建 / 更新请求 */
@Data
public class WorkOrderRequest {

    /** 工单号，全场唯一 */
    private String orderNo;

    /** 机位 id */
    private Long turbineId;

    /** 检修类型 5 选 1 */
    private String maintType;

    /** 工单标题 */
    private String title;

    /** 检修班组 */
    private String crew;

    /** 计划开始时刻（带偏移） */
    private ZonedDateTime planStartTime;

    /** 实际完工时刻（带偏移） */
    private ZonedDateTime actualFinishTime;

    /** PENDING / WORKING / CHECKING / CLOSED */
    private String status;

    /** 缺陷描述 */
    private String faultDesc;

    private String note;
}
