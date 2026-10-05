package com.wind.farm.dto;

import java.math.BigDecimal;
import java.util.List;
import lombok.Data;

@Data
public class GridAllocationRequest {
    private String operator;
    /** MANUAL 模式逐台确认；PROPORTIONAL 可为空，由后台重算 */
    private List<Item> items;
    private String note;

    @Data
    public static class Item {
        private Long turbineId;
        private BigDecimal limitKw;
    }
}
