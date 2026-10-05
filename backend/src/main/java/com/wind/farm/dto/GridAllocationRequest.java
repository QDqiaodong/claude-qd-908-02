package com.wind.farm.dto;

import java.math.BigDecimal;
import java.util.Map;
import lombok.Data;

/** 值班人员逐台确认手动分摊；或对比例分摊结果做启动前复核调整。 */
@Data
public class GridAllocationRequest {

    /** key=turbineId，value=该机位上限 kW */
    private Map<Long, BigDecimal> limits;
    private String operator;
    private String note;
}
