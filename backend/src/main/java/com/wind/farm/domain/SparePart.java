package com.wind.farm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * 备件：备件编号唯一、安全库存不得为负；库存低于安全库存时允许出库但要打预警标记。
 */
@Entity
@Table(name = "spare_part")
@Getter
@Setter
public class SparePart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 备件编号：全场唯一，如 SP-BLADE-001 */
    @Column(name = "part_code", nullable = false, unique = true, length = 32)
    private String partCode;

    /** 备件名称 */
    @Column(name = "part_name", length = 64)
    private String partName;

    /** 规格型号 */
    @Column(name = "spec", length = 64)
    private String spec;

    /** 分类：BLADE 叶片 / GEARBOX 齿轮箱 / GENERATOR 发电机 / PITCH 变桨 / ELECTRIC 电气 / HYDRAULIC 液压 / FASTENER 紧固件 / LUBE 润滑油脂 */
    @Column(name = "category", length = 24)
    private String category;

    /** 单位：件 / 套 / 台 / 支 / 桶 / 米 */
    @Column(name = "unit", length = 8)
    private String unit;

    /** 当前库存 */
    @Column(name = "stock_qty", nullable = false)
    private Integer stockQty;

    /** 安全库存，不得为负 */
    @Column(name = "safety_stock", nullable = false)
    private Integer safetyStock;

    /** 存放位置 */
    @Column(name = "location", length = 32)
    private String location;

    /** 单价（元） */
    @Column(name = "unit_price", precision = 10, scale = 2)
    private BigDecimal unitPrice;

    /** 供应商 */
    @Column(name = "supplier", length = 64)
    private String supplier;

    @Column(name = "note", length = 128)
    private String note;

    @Transient
    public boolean isLowStock() {
        return safetyStock != null && stockQty != null && stockQty < safetyStock;
    }

    /** 库存相对安全库存的充足度（用于库存条） */
    @Transient
    public double getStockRate() {
        if (safetyStock == null || safetyStock <= 0) {
            return stockQty == null || stockQty <= 0 ? 0D : 1D;
        }
        if (stockQty == null) {
            return 0D;
        }
        return Math.min(1D, stockQty * 1D / (safetyStock * 2D));
    }
}
