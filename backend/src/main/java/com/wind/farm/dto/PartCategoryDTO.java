package com.wind.farm.dto;

import lombok.Data;

/**
 * 备件分类库存分布（JPQL select new 用）。
 * 构造器参数个数必须与 JPQL 里 select new 的参数个数完全一致。
 */
@Data
public class PartCategoryDTO {

    private String category;
    private String categoryName;
    private Long partCount;
    private Long totalStock;
    private Long lowStockCount;

    public PartCategoryDTO() {
    }

    public PartCategoryDTO(String category, Long partCount, Long totalStock, Long lowStockCount) {
        this.category = category;
        this.categoryName = null;
        this.partCount = partCount;
        this.totalStock = totalStock;
        this.lowStockCount = lowStockCount;
    }

    public PartCategoryDTO(String category, String categoryName, Long partCount, Long totalStock,
            Long lowStockCount) {
        this.category = category;
        this.categoryName = categoryName;
        this.partCount = partCount;
        this.totalStock = totalStock;
        this.lowStockCount = lowStockCount;
    }
}
