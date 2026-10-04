package com.wind.farm.dto;

import lombok.Data;

/** 备件更换登记请求 */
@Data
public class ReplacementRequest {

    /** 备件 id */
    private Long partId;

    /** 更换到哪个机位 */
    private Long turbineId;

    /** 关联检修工单 id */
    private Long workOrderId;

    /**
     * 客户端幂等令牌：打开登记表单时生成一次，网络重试 / 重复点击沿用同一值，
     * 后端据此保证同一次更换只落一条有效履历。旧客户端可不带（按自然键短窗兜底）。
     */
    private String clientToken;

    /** 更换数量，必须 > 0 且不得超过当前库存 */
    private Integer qty;

    /** 更换人 */
    private String operator;

    private String note;
}
