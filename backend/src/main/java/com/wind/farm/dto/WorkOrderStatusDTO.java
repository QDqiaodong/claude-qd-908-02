package com.wind.farm.dto;

import lombok.Data;

/**
 * 检修工单状态分布（JPQL select new 用）。
 * 构造器参数个数必须与 JPQL 里 select new 的参数个数完全一致。
 */
@Data
public class WorkOrderStatusDTO {

    private String status;
    private String statusName;
    private Long total;

    public WorkOrderStatusDTO() {
    }

    public WorkOrderStatusDTO(String status, Long total) {
        this.status = status;
        this.statusName = null;
        this.total = total;
    }

    public WorkOrderStatusDTO(String status, String statusName, Long total) {
        this.status = status;
        this.statusName = statusName;
        this.total = total;
    }
}
