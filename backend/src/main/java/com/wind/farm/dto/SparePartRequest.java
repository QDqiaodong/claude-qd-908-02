package com.wind.farm.dto;

import java.math.BigDecimal;
import lombok.Data;

/** 备件新建 / 更新请求 */
@Data
public class SparePartRequest {

    /** 备件编号，全场唯一 */
    private String partCode;

    /** 备件名称 */
    private String partName;

    /** 规格型号 */
    private String spec;

    /** 分类 8 选 1 */
    private String category;

    /** 单位 */
    private String unit;

    /** 当前库存 */
    private Integer stockQty;

    /** 安全库存，不得为负 */
    private Integer safetyStock;

    /** 存放位置 */
    private String location;

    /** 单价（元） */
    private BigDecimal unitPrice;

    /** 供应商 */
    private String supplier;

    private String note;
}
