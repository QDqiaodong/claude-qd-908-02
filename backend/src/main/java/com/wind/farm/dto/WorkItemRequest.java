package com.wind.farm.dto;

import lombok.Data;

/** 检修作业项新建 / 更新请求 */
@Data
public class WorkItemRequest {

    /** 所属工单 id */
    private Long workOrderId;

    /** 作业项序号 */
    private Integer itemSeq;

    /** 作业项名称 */
    private String itemName;

    /** 作业标准 */
    private String standard;

    /** 结果：PASS / FAIL / SKIP，为空表示还没做 */
    private String result;

    /** 结果说明 */
    private String resultNote;

    /** 是否已确认 */
    private Boolean done;

    private String note;
}
