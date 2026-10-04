package com.wind.farm.service;

import com.wind.farm.common.BizException;
import com.wind.farm.common.Dict;
import com.wind.farm.common.TimeText;
import com.wind.farm.domain.PartReplacement;
import com.wind.farm.domain.SparePart;
import com.wind.farm.domain.Turbine;
import com.wind.farm.domain.WindFarm;
import com.wind.farm.domain.WorkOrder;
import com.wind.farm.dto.ReplacementRequest;
import com.wind.farm.dto.ReplacementResult;
import com.wind.farm.dto.SparePartRequest;
import com.wind.farm.dto.Views.ReplacementVO;
import com.wind.farm.dto.Views.SparePartVO;
import com.wind.farm.repository.PartReplacementRepository;
import com.wind.farm.repository.SparePartRepository;
import com.wind.farm.repository.TurbineRepository;
import com.wind.farm.repository.WindFarmRepository;
import com.wind.farm.repository.WorkOrderRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 备件与更换：备件编号唯一、安全库存不得为负、库存不得为负；
 * 更换数量 > 0 且不得超过当前库存；库存低于安全库存时仍可出库但要打预警标记。
 *
 * <p>更换登记关联写入链（修复后）：
 * <ol>
 *   <li>机位与工单是一条关联，不是两个互不相干的下拉框——同时传入时必须属于同一机位，冲突一律拒绝；</li>
 *   <li>只传工单时由工单补齐机位（减少重复选择），页面明确展示补齐结果，不做隐式静默填值；</li>
 *   <li>提交时在同一事务内重新核对：工单所属机位、工单可登记状态（仅作业中）、备件可用性与本次数量，
 *       任一不满足都不扣库存、不生成更换记录、不碰工单履历；</li>
 *   <li>库存走「当前库存 ≥ 本次数量」的条件更新，并发领用 / 重试时后到者影响 0 行，库存永不为负；</li>
 *   <li>客户端令牌幂等 + 自然键短窗兜底，同一次更换只留一条有效履历；</li>
 *   <li>旧请求（只带工单 / 只带机位 / 都不带）按明确兼容规则照常登记，冲突旧数据在履历里显示为待核对，
 *       不静默改写、不锁死正常登记。</li>
 * </ol>
 */
@Service
public class SparePartService {

    private final SparePartRepository partRepository;
    private final PartReplacementRepository replacementRepository;
    private final TurbineRepository turbineRepository;
    private final WindFarmRepository farmRepository;
    private final WorkOrderRepository orderRepository;

    public SparePartService(SparePartRepository partRepository, PartReplacementRepository replacementRepository,
            TurbineRepository turbineRepository, WindFarmRepository farmRepository,
            WorkOrderRepository orderRepository) {
        this.partRepository = partRepository;
        this.replacementRepository = replacementRepository;
        this.turbineRepository = turbineRepository;
        this.farmRepository = farmRepository;
        this.orderRepository = orderRepository;
    }

    public List<SparePart> list(String category) {
        if (StringUtils.hasText(category)) {
            return partRepository.findByCategoryOrderByPartCodeAsc(category);
        }
        return partRepository.findAllByOrderByPartCodeAsc();
    }

    public List<SparePartVO> listVO(String category) {
        List<SparePartVO> result = new ArrayList<>();
        for (SparePart part : list(category)) {
            result.add(toVO(part));
        }
        return result;
    }

    public SparePart get(Long id) {
        return partRepository.findById(id).orElseThrow(() -> new BizException("备件不存在：" + id));
    }

    @Transactional
    public SparePart create(SparePartRequest request) {
        if (!StringUtils.hasText(request.getPartCode())) {
            throw new BizException("备件编号不能为空");
        }
        if (partRepository.existsByPartCode(request.getPartCode())) {
            throw new BizException("备件编号已存在：" + request.getPartCode());
        }
        if (request.getCategory() != null && !Dict.isValidPartCategory(request.getCategory())) {
            throw new BizException("备件分类非法：" + request.getCategory());
        }
        if (request.getSafetyStock() != null && request.getSafetyStock() < 0) {
            throw new BizException("安全库存不得为负：" + request.getSafetyStock());
        }
        if (request.getStockQty() != null && request.getStockQty() < 0) {
            throw new BizException("库存不得为负：" + request.getStockQty());
        }
        if (request.getUnitPrice() != null && request.getUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException("单价不得为负");
        }
        SparePart part = new SparePart();
        part.setPartCode(request.getPartCode());
        part.setPartName(request.getPartName());
        part.setSpec(request.getSpec());
        part.setCategory(request.getCategory());
        part.setUnit(request.getUnit());
        part.setStockQty(request.getStockQty());
        part.setSafetyStock(request.getSafetyStock());
        part.setLocation(request.getLocation());
        part.setUnitPrice(request.getUnitPrice());
        part.setSupplier(request.getSupplier());
        part.setNote(request.getNote());
        // 默认值写在 create 里，实体字段不带默认值
        if (part.getStockQty() == null) {
            part.setStockQty(0);
        }
        if (part.getSafetyStock() == null) {
            part.setSafetyStock(0);
        }
        if (part.getUnit() == null) {
            part.setUnit("件");
        }
        return partRepository.save(part);
    }

    @Transactional
    public SparePart update(Long id, SparePartRequest request) {
        SparePart part = get(id);
        if (StringUtils.hasText(request.getPartCode())
                && !request.getPartCode().equals(part.getPartCode())
                && partRepository.existsByPartCode(request.getPartCode())) {
            throw new BizException("备件编号已存在：" + request.getPartCode());
        }
        if (request.getCategory() != null && !Dict.isValidPartCategory(request.getCategory())) {
            throw new BizException("备件分类非法：" + request.getCategory());
        }
        if (request.getSafetyStock() != null && request.getSafetyStock() < 0) {
            throw new BizException("安全库存不得为负：" + request.getSafetyStock());
        }
        if (request.getStockQty() != null && request.getStockQty() < 0) {
            throw new BizException("库存不得为负：" + request.getStockQty());
        }
        ifNotNull(request.getPartCode(), part::setPartCode);
        ifNotNull(request.getPartName(), part::setPartName);
        ifNotNull(request.getSpec(), part::setSpec);
        ifNotNull(request.getCategory(), part::setCategory);
        ifNotNull(request.getUnit(), part::setUnit);
        ifNotNull(request.getStockQty(), part::setStockQty);
        ifNotNull(request.getSafetyStock(), part::setSafetyStock);
        ifNotNull(request.getLocation(), part::setLocation);
        ifNotNull(request.getUnitPrice(), part::setUnitPrice);
        ifNotNull(request.getSupplier(), part::setSupplier);
        ifNotNull(request.getNote(), part::setNote);
        return partRepository.save(part);
    }

    /** 步进器改库存：− / + 直接增减当前库存；− 方向走条件更新，库存永不为负 */
    @Transactional
    public SparePart changeStock(Long id, Integer delta) {
        if (delta == null) {
            throw new BizException("库存变化量不能为空");
        }
        if (delta > Dict.MAX_REPLACE_QTY || delta < -Dict.MAX_REPLACE_QTY) {
            throw new BizException("单次库存调整不得超过 " + Dict.MAX_REPLACE_QTY);
        }
        SparePart part = get(id);
        int rows;
        if (delta >= 0) {
            rows = partRepository.increaseStock(id, delta);
        } else {
            int need = -delta;
            rows = partRepository.deductStock(id, need);
            if (rows == 0) {
                SparePart current = get(id);
                throw new BizException("库存不得为负，当前库存 " + nvl(current.getStockQty())
                        + " " + current.getUnit() + "，本次需出库 " + need + "，请刷新后重试");
            }
        }
        if (rows == 0) {
            throw new BizException("库存调整失败：备件可能已被删除，请刷新后重试");
        }
        return partRepository.findById(id).orElseThrow(() -> new BizException("备件不存在：" + id));
    }

    @Transactional
    public void remove(Long id) {
        SparePart part = get(id);
        if (!replacementRepository.findByPartIdOrderByReplaceTimeDesc(id).isEmpty()) {
            throw new BizException("该备件已有更换记录，不允许删除");
        }
        partRepository.delete(part);
    }

    // ---------------- 更换登记 ----------------

    public List<PartReplacement> replacements(Long partId, Long turbineId) {
        if (partId != null) {
            return replacementRepository.findByPartIdOrderByReplaceTimeDesc(partId);
        }
        if (turbineId != null) {
            return replacementRepository.findByTurbineIdOrderByReplaceTimeDesc(turbineId);
        }
        return replacementRepository.findAllByOrderByReplaceTimeDesc();
    }

    public List<ReplacementVO> replacementVOs(Long partId, Long turbineId) {
        List<ReplacementVO> result = new ArrayList<>();
        for (PartReplacement item : replacements(partId, turbineId)) {
            result.add(toReplacementVO(item));
        }
        return result;
    }

    /**
     * 更换登记（整条关联写入链在同一事务内）：
     * 重新核对工单所属机位 / 工单状态 / 备件库存 / 本次数量，任一不满足即整体不写；
     * 库存条件更新防并发负数，令牌 / 自然键幂等防重试重复履历。
     */
    @Transactional
    public ReplacementResult replace(ReplacementRequest request) {
        if (request == null || request.getPartId() == null) {
            throw new BizException("请选择要更换的备件");
        }
        SparePart part = get(request.getPartId());
        if (request.getQty() == null || request.getQty() <= 0) {
            throw new BizException("更换数量必须大于 0");
        }
        if (request.getQty() > Dict.MAX_REPLACE_QTY) {
            throw new BizException("单次更换数量不得超过 " + Dict.MAX_REPLACE_QTY);
        }

        String token = StringUtils.hasText(request.getClientToken()) ? request.getClientToken().trim() : null;
        if (token != null && token.length() > 64) {
            token = token.substring(0, 64);
        }

        boolean hasTurbine = request.getTurbineId() != null;
        boolean hasOrder = request.getWorkOrderId() != null;

        Turbine turbine = null;
        WorkOrder order = null;
        if (hasTurbine) {
            turbine = turbineRepository.findById(request.getTurbineId()).orElse(null);
            if (turbine == null) {
                throw new BizException("所选机位不存在或已被删除，请改选有效机位后重新提交");
            }
        }
        if (hasOrder) {
            order = orderRepository.findById(request.getWorkOrderId()).orElse(null);
            if (order == null) {
                throw new BizException("所选检修工单不存在或已被删除，请改选有效工单后重新提交");
            }
        }

        boolean filledFromOrder = false;
        if (hasOrder) {
            // 工单与机位的归属以工单为准：同时传入时任何不一致都必须拒绝，绝不靠前端编号比对
            WorkOrder liveOrder = order;
            Turbine orderTurbine = turbineRepository.findById(liveOrder.getTurbineId()).orElse(null);
            if (hasTurbine) {
                if (!request.getTurbineId().equals(liveOrder.getTurbineId())) {
                    String picked = turbine.getUnitCode();
                    String belong = orderTurbine == null ? ("机位#" + liveOrder.getTurbineId())
                            : orderTurbine.getUnitCode();
                    throw new BizException("机位与工单不属于同一台机组：工单 " + liveOrder.getOrderNo()
                            + " 属于机位 " + belong + "，当前选的是机位 " + picked
                            + "；请把机位改选为 " + belong + "，或改选机位 " + picked + " 下的工单后再提交");
                }
            } else {
                // 只选工单：由工单补齐机位，减少重复选择（不做隐式填值——结果会在页面明确回显）
                if (orderTurbine == null) {
                    throw new BizException("工单 " + liveOrder.getOrderNo() + " 所属机位已不存在，"
                            + "无法补齐机位，请改选机位与工单一致的有效工单后重新提交");
                }
                turbine = orderTurbine;
                filledFromOrder = true;
            }
            // 提交时重新核对工单可登记状态：只有作业中可以领用，待派 / 待验收 / 已闭环一律拒绝
            if (!Dict.isOrderAcceptingReplacement(liveOrder.getStatus())) {
                throw new BizException("工单 " + liveOrder.getOrderNo() + " 当前为「"
                        + Dict.workOrderStatusName(liveOrder.getStatus())
                        + "」，不是作业中，不能登记备件更换；请改用该机位作业中的工单"
                        + (filledFromOrder ? "（机位 " + turbine.getUnitCode() + "）" : "") + "后重新提交");
            }
        }

        // 幂等 1：客户端令牌——同一次更换（重试 / 重复点击）直接返回已生效记录，不再扣减、不再落履历
        if (token != null) {
            PartReplacement existing = replacementRepository.findByClientToken(token).orElse(null);
            if (existing != null) {
                return ReplacementResult.duplicated(existing);
            }
        } else {
            // 幂等 2：旧客户端无令牌，按自然键短窗兜底（只拦极可能是网络重试的同内容提交，不误并正常连续登记）
            LocalDateTime since = LocalDateTime.now().minusSeconds(Dict.REPLACE_DEDUP_WINDOW_SECONDS);
            List<PartReplacement> recent = replacementRepository.findRecentNatural(part.getId(),
                    turbine == null ? null : turbine.getId(), order == null ? null : order.getId(),
                    request.getQty(), emptyToNull(request.getOperator()), since);
            if (!recent.isEmpty()) {
                return ReplacementResult.duplicated(recent.get(0));
            }
        }

        // 提交时重新核对备件可用性与本次数量。先给一条友好的余量提示，
        // 真正的裁决是下面的条件更新（数据库当前库存 ≥ 数量才更新），并发下库存永不为负。
        int currentStock = nvl(part.getStockQty());
        if (request.getQty() > currentStock) {
            throw new BizException("备件 " + part.getPartCode() + " 当前库存仅 " + currentStock + " "
                    + part.getUnit() + "，本次需更换 " + request.getQty() + "，请刷新后按真实余量登记");
        }

        int rows = partRepository.deductStock(part.getId(), request.getQty());
        if (rows == 0) {
            // 并发：两人同时领用或重试时，先提交者已扣减；本条整体失败，履历与工单履历都不写
            SparePart latest = partRepository.findById(part.getId())
                    .orElseThrow(() -> new BizException("备件已被删除，请刷新后重试"));
            throw new BizException("备件 " + latest.getPartCode() + " 库存刚刚变化，当前剩余 "
                    + nvl(latest.getStockQty()) + " " + latest.getUnit() + "，不足以更换 "
                    + request.getQty() + "；请刷新后按真实余量重新登记");
        }
        SparePart afterPart = partRepository.findById(part.getId()).orElseThrow();
        int after = nvl(afterPart.getStockQty());

        PartReplacement record = new PartReplacement();
        record.setPartId(afterPart.getId());
        record.setPartCode(afterPart.getPartCode());
        record.setPartName(afterPart.getPartName());
        record.setTurbineId(turbine == null ? null : turbine.getId());
        record.setUnitCode(turbine == null ? null : turbine.getUnitCode());
        record.setWorkOrderId(order == null ? null : order.getId());
        record.setOrderNo(order == null ? null : order.getOrderNo());
        record.setQty(request.getQty());
        record.setReplaceTime(LocalDateTime.now());
        record.setOperator(request.getOperator());
        record.setStockAfter(after);
        record.setClientToken(token);
        record.setSource(resolveSource(token != null, hasTurbine, hasOrder));
        // 低于安全库存仍可出库，但打预警标记
        boolean warn = after < nvl(afterPart.getSafetyStock());
        record.setWarningFlag(warn);
        record.setNote(warn
                ? "出库后库存 " + after + " 低于安全库存 " + nvl(afterPart.getSafetyStock()) + "，已标记预警"
                : request.getNote());

        try {
            return ReplacementResult.created(replacementRepository.saveAndFlush(record), filledFromOrder);
        } catch (DataIntegrityViolationException e) {
            // 并发兜底：两条相同令牌的登记同时到达，唯一索引只放一条，另一条回滚库存扣减
            throw new BizException("该更换登记刚刚已提交成功（可能为重复点击或网络重试），未重复扣减；"
                    + "请刷新查看真实剩余量与已生效的更换记录");
        }
    }

    /** 登记来源分类：新客户端按是否带令牌识别；旧请求保留四种兼容形态，便于待核对时追溯 */
    private String resolveSource(boolean hasToken, boolean hasTurbine, boolean hasOrder) {
        if (hasToken) {
            return Dict.SOURCE_NEW;
        }
        if (hasTurbine && hasOrder) {
            return Dict.SOURCE_LEGACY_BOTH;
        }
        if (hasOrder) {
            return Dict.SOURCE_LEGACY_ORDER_ONLY;
        }
        if (hasTurbine) {
            return Dict.SOURCE_LEGACY_TURBINE_ONLY;
        }
        return Dict.SOURCE_LEGACY_NONE;
    }

    // ---------------- VO ----------------

    public SparePartVO toVO(SparePart part) {
        SparePartVO vo = new SparePartVO();
        vo.setId(part.getId());
        vo.setPartCode(part.getPartCode());
        vo.setPartName(part.getPartName());
        vo.setSpec(part.getSpec());
        vo.setCategory(part.getCategory());
        vo.setCategoryName(Dict.partCategoryName(part.getCategory()));
        vo.setUnit(part.getUnit());
        vo.setStockQty(part.getStockQty());
        vo.setSafetyStock(part.getSafetyStock());
        vo.setLocation(part.getLocation());
        vo.setUnitPrice(part.getUnitPrice());
        vo.setSupplier(part.getSupplier());
        vo.setLowStock(part.isLowStock());
        vo.setStockRate(part.getStockRate());
        vo.setNote(part.getNote());
        return vo;
    }

    public ReplacementVO toReplacementVO(PartReplacement record) {
        ReplacementVO vo = new ReplacementVO();
        vo.setId(record.getId());
        vo.setPartId(record.getPartId());
        vo.setPartCode(record.getPartCode());
        vo.setPartName(record.getPartName());
        vo.setTurbineId(record.getTurbineId());
        vo.setUnitCode(record.getUnitCode());
        vo.setSource(record.getSource());
        vo.setSourceName(Dict.replacementSourceName(record.getSource()));
        Turbine turbine = record.getTurbineId() == null
                ? null
                : turbineRepository.findById(record.getTurbineId()).orElse(null);
        if (turbine != null && turbine.getFarmId() != null) {
            WindFarm farm = farmRepository.findById(turbine.getFarmId()).orElse(null);
            vo.setFarmName(farm == null ? null : farm.getFarmName());
        }
        vo.setWorkOrderId(record.getWorkOrderId());
        vo.setOrderNo(record.getOrderNo());
        WorkOrder linkedOrder = record.getWorkOrderId() == null
                ? null
                : orderRepository.findById(record.getWorkOrderId()).orElse(null);
        if (linkedOrder != null) {
            vo.setOrderStatus(linkedOrder.getStatus());
            vo.setOrderStatusName(Dict.workOrderStatusName(linkedOrder.getStatus()));
            Turbine actualTurbine = turbineRepository.findById(linkedOrder.getTurbineId()).orElse(null);
            vo.setActualOrderUnitCode(actualTurbine == null ? null : actualTurbine.getUnitCode());
        }
        vo.setQty(record.getQty());
        vo.setReplaceTime(TimeText.dateTime(record.getReplaceTime()));
        vo.setOperator(record.getOperator());
        vo.setWarningFlag(record.getWarningFlag());
        vo.setStockAfter(record.getStockAfter());
        vo.setNote(record.getNote());

        // 实时核对：关联对象缺失 / 机位与工单归属不一致 / 机位编号对不上，都标成待核对并说明应改选谁。
        // 只读出结论，不回写历史行——冲突旧数据必须看得见，不能被静默改写。
        vo.setPendingCheck(false);
        if (record.getWorkOrderId() != null && linkedOrder == null) {
            markPending(vo, "关联工单已删除（原工单号 " + nullToDash(record.getOrderNo())
                    + "），请核对实际工单后重新关联");
        } else if (record.getTurbineId() != null && turbine == null) {
            markPending(vo, "登记机位已删除（原机位 " + nullToDash(record.getUnitCode())
                    + "），请核对实际机位后重新关联");
        } else if (linkedOrder != null && turbine != null
                && !linkedOrder.getTurbineId().equals(turbine.getId())) {
            markPending(vo, "机位与工单不一致：工单 " + linkedOrder.getOrderNo() + " 实际属于机位 "
                    + nullToDash(vo.getActualOrderUnitCode()) + "，登记写的是机位 "
                    + nullToDash(record.getUnitCode()) + "，请核对后改选一致的机位 / 工单");
        } else if (linkedOrder != null && turbine != null
                && StringUtils.hasText(linkedOrder.getUnitCode())
                && !linkedOrder.getUnitCode().equals(turbine.getUnitCode())) {
            markPending(vo, "机位编号与工单不符：工单 " + linkedOrder.getOrderNo() + " 所属机位编号为 "
                    + linkedOrder.getUnitCode() + "，登记写的是 " + record.getUnitCode()
                    + "，请核对后改选一致的机位");
        }
        return vo;
    }

    private void markPending(ReplacementVO vo, String note) {
        vo.setPendingCheck(true);
        vo.setCheckNote(note);
    }

    private static String nullToDash(String value) {
        return value == null ? "—" : value;
    }

    private static String emptyToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static int nvl(Integer value) {
        return value == null ? 0 : value;
    }

    private static <T> void ifNotNull(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}
