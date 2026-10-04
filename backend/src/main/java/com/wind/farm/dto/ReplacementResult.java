package com.wind.farm.dto;

import com.wind.farm.domain.PartReplacement;
import lombok.Data;

/**
 * 更换登记结果：除落库记录外，携带本次写入的关联判定信息，
 * 供页面给出「是否重复提交、机位是否由工单补齐」的明确提示。
 */
@Data
public class ReplacementResult {

    /** 本次有效（或幂等命中的已存在）更换记录 */
    private PartReplacement record;

    /** true：命中幂等，此前已生效，本次没有重复扣减、没有重复履历 */
    private boolean duplicated;

    /** true：只选了工单，机位由工单归属补齐（登记人需要在页面看到补齐结果） */
    private boolean turbineFilledFromOrder;

    /** 补齐 / 命中时对应的机位编号、工单号，供页面提示 */
    private String unitCode;

    private String orderNo;

    public static ReplacementResult created(PartReplacement record, boolean turbineFilledFromOrder) {
        ReplacementResult r = new ReplacementResult();
        r.setRecord(record);
        r.setDuplicated(false);
        r.setTurbineFilledFromOrder(turbineFilledFromOrder);
        r.setUnitCode(record.getUnitCode());
        r.setOrderNo(record.getOrderNo());
        return r;
    }

    public static ReplacementResult duplicated(PartReplacement record) {
        ReplacementResult r = new ReplacementResult();
        r.setRecord(record);
        r.setDuplicated(true);
        r.setTurbineFilledFromOrder(false);
        r.setUnitCode(record.getUnitCode());
        r.setOrderNo(record.getOrderNo());
        return r;
    }
}
