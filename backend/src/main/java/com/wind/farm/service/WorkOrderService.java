package com.wind.farm.service;

import com.wind.farm.common.BizException;
import com.wind.farm.common.Dict;
import com.wind.farm.common.TimeText;
import com.wind.farm.domain.SafetyTicket;
import com.wind.farm.domain.Turbine;
import com.wind.farm.domain.WindFarm;
import com.wind.farm.domain.WorkItem;
import com.wind.farm.domain.WorkOrder;
import com.wind.farm.dto.AcceptanceRequest;
import com.wind.farm.dto.WorkItemRequest;
import com.wind.farm.dto.WorkOrderRequest;
import com.wind.farm.dto.Views.WorkItemVO;
import com.wind.farm.dto.Views.WorkOrderVO;
import com.wind.farm.repository.TurbineRepository;
import com.wind.farm.repository.WindFarmRepository;
import com.wind.farm.repository.WorkItemRepository;
import com.wind.farm.repository.WorkOrderRepository;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 检修工单与作业项：工单号唯一、检修类型 5 选 1、
 * 状态流转 待派 → 作业中 → 待验收 → 已闭环（跳阶段拦截）、
 * 提验收前所有作业项必须都有结果、同一机位不能同时存在两张作业中的工单。
 */
@Service
public class WorkOrderService {

    private final WorkOrderRepository orderRepository;
    private final WorkItemRepository itemRepository;
    private final TurbineRepository turbineRepository;
    private final WindFarmRepository farmRepository;
    private final SafetyTicketService ticketService;

    public WorkOrderService(WorkOrderRepository orderRepository, WorkItemRepository itemRepository,
            TurbineRepository turbineRepository, WindFarmRepository farmRepository,
            @Lazy SafetyTicketService ticketService) {
        this.orderRepository = orderRepository;
        this.itemRepository = itemRepository;
        this.turbineRepository = turbineRepository;
        this.farmRepository = farmRepository;
        this.ticketService = ticketService;
    }

    public List<WorkOrder> list(Long turbineId, Long farmId) {
        if (turbineId != null) {
            return orderRepository.findByTurbineIdOrderByOrderNoAsc(turbineId);
        }
        if (farmId != null) {
            return orderRepository.findByFarmIdOrderByOrderNoAsc(farmId);
        }
        return orderRepository.findAllByOrderByOrderNoAsc();
    }

    public List<WorkOrderVO> listVO(Long turbineId, Long farmId) {
        List<WorkOrderVO> result = new ArrayList<>();
        for (WorkOrder order : list(turbineId, farmId)) {
            result.add(toVO(order));
        }
        return result;
    }

    public WorkOrder get(Long id) {
        return orderRepository.findById(id).orElseThrow(() -> new BizException("检修工单不存在：" + id));
    }

    @Transactional
    public WorkOrder create(WorkOrderRequest request) {
        if (!StringUtils.hasText(request.getOrderNo())) {
            throw new BizException("工单号不能为空");
        }
        if (orderRepository.existsByOrderNo(request.getOrderNo())) {
            throw new BizException("工单号已存在：" + request.getOrderNo());
        }
        if (request.getTurbineId() == null) {
            throw new BizException("请选择工单所属机位");
        }
        Turbine turbine = turbineRepository.findById(request.getTurbineId())
                .orElseThrow(() -> new BizException("机位不存在：" + request.getTurbineId()));
        if (!StringUtils.hasText(request.getMaintType())) {
            throw new BizException("检修类型不能为空");
        }
        if (!Dict.isValidMaintType(request.getMaintType())) {
            throw new BizException("检修类型只能为 定期维护 / 临时检修 / 紧急抢修 / 技术改造 / 大修：" + request.getMaintType());
        }
        String status = request.getStatus() == null ? "PENDING" : request.getStatus();
        if (!Dict.isValidWorkOrderStatus(status)) {
            throw new BizException("工单状态只能为 待派 / 作业中 / 待验收 / 已闭环：" + status);
        }
        if ("WORKING".equals(status)) {
            assertNoWorkingOrder(turbine.getId(), null);
            // 新建即作业中也必须已有「已隔离」的隔离票（通常票先于工单作业存在）
            SafetyTicket ticket = ticketService.activeTicketOfTurbine(turbine.getId());
            if (ticket == null || !Dict.isIsolationEffective(ticket.getStatus())) {
                throw new BizException("机位 " + turbine.getUnitCode()
                        + " 的安全隔离票未达到「已隔离」，工单不能进入作业中");
            }
        }
        WorkOrder order = new WorkOrder();
        order.setOrderNo(request.getOrderNo());
        order.setTurbineId(turbine.getId());
        order.setFarmId(turbine.getFarmId());
        order.setUnitCode(turbine.getUnitCode());
        order.setMaintType(request.getMaintType());
        order.setTitle(request.getTitle());
        order.setCrew(request.getCrew());
        order.setPlanStartTime(request.getPlanStartTime());
        order.setPlanZone(request.getPlanStartTime() == null ? null : zoneOf(turbine.getFarmId()).getId());
        order.setActualFinishTime(request.getActualFinishTime());
        order.setFinishZone(request.getActualFinishTime() == null ? null : zoneOf(turbine.getFarmId()).getId());
        order.setStatus(status);
        order.setFaultDesc(request.getFaultDesc());
        order.setNote(request.getNote());
        return orderRepository.save(order);
    }

    @Transactional
    public WorkOrder update(Long id, WorkOrderRequest request) {
        WorkOrder order = get(id);
        assertNotClosed(order);
        if (StringUtils.hasText(request.getOrderNo())
                && !request.getOrderNo().equals(order.getOrderNo())
                && orderRepository.existsByOrderNo(request.getOrderNo())) {
            throw new BizException("工单号已存在：" + request.getOrderNo());
        }
        if (request.getMaintType() != null && !Dict.isValidMaintType(request.getMaintType())) {
            throw new BizException("检修类型只能为 定期维护 / 临时检修 / 紧急抢修 / 技术改造 / 大修：" + request.getMaintType());
        }
        if (request.getStatus() != null && !Dict.isValidWorkOrderStatus(request.getStatus())) {
            throw new BizException("工单状态只能为 待派 / 作业中 / 待验收 / 已闭环：" + request.getStatus());
        }
        if (request.getTurbineId() != null && !request.getTurbineId().equals(order.getTurbineId())) {
            if (ticketService.getByOrder(id) != null) {
                throw new BizException("工单已创建安全隔离票，机位与隔离措施一一对应，不能再更换机位；"
                        + "请在原机位完成隔离闭环，或另开工单");
            }
            Turbine turbine = turbineRepository.findById(request.getTurbineId())
                    .orElseThrow(() -> new BizException("机位不存在：" + request.getTurbineId()));
            order.setTurbineId(turbine.getId());
            order.setFarmId(turbine.getFarmId());
            order.setUnitCode(turbine.getUnitCode());
        }
        ifNotNull(request.getOrderNo(), order::setOrderNo);
        ifNotNull(request.getMaintType(), order::setMaintType);
        ifNotNull(request.getTitle(), order::setTitle);
        ifNotNull(request.getCrew(), order::setCrew);
        ifNotNull(request.getPlanStartTime(), order::setPlanStartTime);
        if (request.getPlanStartTime() != null) {
            order.setPlanZone(zoneOf(order.getFarmId()).getId());
        }
        ifNotNull(request.getActualFinishTime(), order::setActualFinishTime);
        if (request.getActualFinishTime() != null) {
            order.setFinishZone(zoneOf(order.getFarmId()).getId());
        }
        ifNotNull(request.getFaultDesc(), order::setFaultDesc);
        ifNotNull(request.getNote(), order::setNote);
        if (request.getStatus() != null && !request.getStatus().equals(order.getStatus())) {
            if (!Dict.isValidOrderFlow(order.getStatus(), request.getStatus())) {
                throw new BizException("不允许跳阶段流转：" + Dict.orderFlowHint(order.getStatus()));
            }
            if ("WORKING".equals(request.getStatus())) {
                assertNoWorkingOrder(order.getTurbineId(), id);
                assertIsolatedForWorking(order, null);
            }
            if ("CHECKING".equals(request.getStatus())) {
                assertAllItemsResulted(order);
            }
            if ("CLOSED".equals(request.getStatus())) {
                assertAllItemsResulted(order);
                assertRestoredForClose(order);
                order.setAcceptResult("PASS");
                if (!StringUtils.hasText(order.getAcceptPerson())) {
                    order.setAcceptPerson(request.getCrew() == null ? null : request.getCrew());
                }
                order.setActualFinishTime(ZonedDateTime.now(zoneOf(order.getFarmId())));
            order.setFinishZone(zoneOf(order.getFarmId()).getId());
            }
            order.setStatus(request.getStatus());
        }
        return orderRepository.save(order);
    }

    /** 阶段推进：待派 → 作业中 → 待验收 → 已闭环，跳阶段一律拦截 */
    @Transactional
    public WorkOrder changeStatus(Long id, String status) {
        if (!Dict.isValidWorkOrderStatus(status)) {
            throw new BizException("工单状态只能为 待派 / 作业中 / 待验收 / 已闭环：" + status);
        }
        WorkOrder order = get(id);
        assertNotClosed(order);
        if (order.getStatus() != null && order.getStatus().equals(status)) {
            return order;
        }
        if (!Dict.isValidOrderFlow(order.getStatus(), status)) {
            throw new BizException("工单 " + order.getOrderNo() + " 不允许跳阶段流转："
                    + Dict.orderFlowHint(order.getStatus()));
        }
        if ("WORKING".equals(status)) {
            assertNoWorkingOrder(order.getTurbineId(), id);
            assertIsolatedForWorking(order, null);
        }
        if ("CHECKING".equals(status)) {
            assertAllItemsResulted(order);
            // 提交验收后此前的验收结论失效，需要验收人重新登记
            order.setAcceptResult(null);
            order.setAcceptPerson(null);
            order.setAcceptTime(null);
            order.setAcceptZone(null);
        }
        if ("CLOSED".equals(status)) {
            assertAllItemsResulted(order);
            assertRestoredForClose(order);
            order.setActualFinishTime(ZonedDateTime.now(zoneOf(order.getFarmId())));
            order.setFinishZone(zoneOf(order.getFarmId()).getId());
            if (!StringUtils.hasText(order.getAcceptPerson())) {
                order.setAcceptPerson(order.getCrew());
            }
        }
        order.setStatus(status);
        return orderRepository.save(order);
    }

    /**
     * 验收登记：待验收阶段由验收人填写结论。
     * 合格 PASS：作为隔离票开始复电的前置条件（不改变工单状态，复电恢复后才允许闭环）；
     * 驳回 FAIL：工单退回作业中，验收结论记为驳回，隔离票保持已隔离。
     */
    @Transactional
    public WorkOrder accept(Long id, AcceptanceRequest request) {
        if (request == null || !Dict.isValidAcceptResult(request.getResult())) {
            throw new BizException("验收结论只能为 验收合格 / 验收驳回");
        }
        if (!StringUtils.hasText(request.getPerson())) {
            throw new BizException("请填写验收人");
        }
        WorkOrder order = get(id);
        if (!"CHECKING".equals(order.getStatus())) {
            throw new BizException("只有待验收的工单才能登记验收结论，当前为「"
                    + Dict.workOrderStatusName(order.getStatus()) + "」");
        }
        order.setAcceptResult(request.getResult());
        order.setAcceptPerson(request.getPerson().trim());
        order.setAcceptNote(request.getNote());
        order.setAcceptTime(ZonedDateTime.now(zoneOf(order.getFarmId())));
        order.setAcceptZone(zoneOf(order.getFarmId()).getId());
        if ("FAIL".equals(request.getResult())) {
            // 驳回：退回作业中，隔离措施保持，作业人员整改后重新提交验收
            order.setStatus("WORKING");
        }
        return orderRepository.save(order);
    }

    @Transactional
    public void remove(Long id) {
        WorkOrder order = get(id);
        if ("WORKING".equals(order.getStatus())) {
            throw new BizException("作业中的工单不允许删除，请先闭环");
        }
        SafetyTicket ticket = ticketService.getByOrder(id);
        if (ticket != null && !"DRAFT".equals(ticket.getStatus())) {
            throw new BizException("工单存在已开始确认的安全隔离票 " + ticket.getTicketNo()
                    + "，不能删除（隔离记录须保留可追溯）");
        }
        if (ticket != null) {
            // 草稿票随工单一并删除
            ticketService.deleteTicket(ticket.getId());
        }
        itemRepository.deleteAll(itemRepository.findByWorkOrderIdOrderByItemSeqAsc(id));
        orderRepository.delete(order);
    }

    /** 未达到「已隔离」（含复电中）时，工单不能进入作业中 */
    private void assertIsolatedForWorking(WorkOrder order, String orderNoHint) {
        SafetyTicket ticket = order == null ? null : ticketService.getByOrder(order.getId());
        if (ticket == null) {
            throw new BizException("工单 " + (order == null ? orderNoHint : order.getOrderNo())
                    + " 还没有安全隔离票，请先建票并完成停机、验电、机械锁定等隔离确认");
        }
        if (!Dict.isIsolationEffective(ticket.getStatus())) {
            throw new BizException("隔离票 " + ticket.getTicketNo() + " 当前为「"
                    + Dict.ticketStatusName(ticket.getStatus()) + "」，未达到「已隔离」，工单不能进入作业中");
        }
    }

    /** 未达到「已恢复」时，工单不能闭环，机位也不能恢复并网（并网游标在 TurbineService 再拦一次） */
    private void assertRestoredForClose(WorkOrder order) {
        SafetyTicket ticket = ticketService.getByOrder(order.getId());
        if (ticket == null) {
            throw new BizException("工单 " + order.getOrderNo() + " 没有安全隔离票，不能闭环");
        }
        if (!"RESTORED".equals(ticket.getStatus())) {
            throw new BizException("隔离票 " + ticket.getTicketNo() + " 当前为「"
                    + Dict.ticketStatusName(ticket.getStatus())
                    + "」，未达到「已恢复」，工单不能闭环；请先验收合格并按逆序完成复电");
        }
        if (!"PASS".equals(order.getAcceptResult())) {
            throw new BizException("工单尚未登记「验收合格」，不能闭环");
        }
    }

    // ---------------- 作业项 ----------------

    public List<WorkItem> items(Long workOrderId) {
        return itemRepository.findByWorkOrderIdOrderByItemSeqAsc(workOrderId);
    }

    @Transactional
    public WorkItem createItem(WorkItemRequest request) {
        if (request.getWorkOrderId() == null) {
            throw new BizException("请选择作业项所属工单");
        }
        WorkOrder order = get(request.getWorkOrderId());
        assertNotClosed(order);
        if (!StringUtils.hasText(request.getItemName())) {
            throw new BizException("作业项名称不能为空");
        }
        if (request.getResult() != null && !Dict.isValidItemResult(request.getResult())) {
            throw new BizException("作业项结果只能为 合格 / 不合格 / 免检：" + request.getResult());
        }
        WorkItem item = new WorkItem();
        item.setWorkOrderId(order.getId());
        item.setItemSeq(request.getItemSeq() == null ? items(order.getId()).size() + 1 : request.getItemSeq());
        item.setItemName(request.getItemName());
        item.setStandard(request.getStandard());
        item.setResult(request.getResult());
        item.setResultNote(request.getResultNote());
        item.setDone(request.getDone());
        item.setNote(request.getNote());
        if (item.getDone() == null) {
            item.setDone(Boolean.FALSE);
        }
        return itemRepository.save(item);
    }

    /** 勾选 / 填写作业项结果：done 与 result 一起维护 */
    @Transactional
    public WorkItem updateItem(Long id, WorkItemRequest request) {
        WorkItem item = itemRepository.findById(id).orElseThrow(() -> new BizException("作业项不存在：" + id));
        WorkOrder order = get(item.getWorkOrderId());
        assertNotClosed(order);
        if (request.getResult() != null && !Dict.isValidItemResult(request.getResult())) {
            throw new BizException("作业项结果只能为 合格 / 不合格 / 免检：" + request.getResult());
        }
        ifNotNull(request.getItemSeq(), item::setItemSeq);
        ifNotNull(request.getItemName(), item::setItemName);
        ifNotNull(request.getStandard(), item::setStandard);
        ifNotNull(request.getResult(), item::setResult);
        ifNotNull(request.getResultNote(), item::setResultNote);
        ifNotNull(request.getNote(), item::setNote);
        if (request.getDone() != null) {
            item.setDone(request.getDone());
        }
        return itemRepository.save(item);
    }

    @Transactional
    public void removeItem(Long id) {
        WorkItem item = itemRepository.findById(id).orElseThrow(() -> new BizException("作业项不存在：" + id));
        assertNotClosed(get(item.getWorkOrderId()));
        itemRepository.delete(item);
    }

    // ---------------- 规则校验 ----------------

    /** 同一机位不能同时存在两张作业中的工单 */
    private void assertNoWorkingOrder(Long turbineId, Long excludeOrderId) {
        long working = orderRepository.countByTurbineIdAndStatus(turbineId, "WORKING");
        if (excludeOrderId != null) {
            WorkOrder self = orderRepository.findById(excludeOrderId).orElse(null);
            if (self != null && "WORKING".equals(self.getStatus())) {
                working -= 1;
            }
        }
        if (working > 0) {
            Turbine turbine = turbineRepository.findById(turbineId).orElse(null);
            throw new BizException("机位 " + (turbine == null ? turbineId : turbine.getUnitCode())
                    + " 已有一张作业中的工单，不能同时开工两张");
        }
    }

    /** 提验收前所有作业项必须都有结果 */
    private void assertAllItemsResulted(WorkOrder order) {
        List<WorkItem> items = items(order.getId());
        if (items.isEmpty()) {
            throw new BizException("工单 " + order.getOrderNo() + " 还没有作业项，不能提交验收");
        }
        for (WorkItem item : items) {
            if (!StringUtils.hasText(item.getResult())) {
                throw new BizException("作业项「" + item.getItemName() + "」还没有结果，全部作业项有结果后才能提交验收");
            }
        }
    }

    private void assertNotClosed(WorkOrder order) {
        if ("CLOSED".equals(order.getStatus())) {
            throw new BizException("工单 " + order.getOrderNo() + " 已闭环，不允许再修改");
        }
    }

    private java.time.ZoneId zoneOf(Long farmId) {
        WindFarm farm = farmId == null ? null : farmRepository.findById(farmId).orElse(null);
        return Dict.zoneOf(farm == null ? null : farm.getTimeZone());
    }

    // ---------------- VO ----------------

    public WorkOrderVO toVO(WorkOrder order) {
        WorkOrderVO vo = new WorkOrderVO();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setTurbineId(order.getTurbineId());
        vo.setFarmId(order.getFarmId());
        WindFarm farm = order.getFarmId() == null ? null : farmRepository.findById(order.getFarmId()).orElse(null);
        String zone = farm == null ? null : farm.getTimeZone();
        vo.setFarmName(farm == null ? null : farm.getFarmName());
        vo.setUnitCode(order.getUnitCode());
        vo.setMaintType(order.getMaintType());
        vo.setMaintTypeName(Dict.maintTypeName(order.getMaintType()));
        vo.setMaintTypeColor(Dict.maintTypeColor(order.getMaintType()));
        vo.setTitle(order.getTitle());
        vo.setCrew(order.getCrew());
        vo.setPlanStartTime(TimeText.iso(order.getPlanStartTime()));
        vo.setPlanTz(order.getPlanZone());
        vo.setPlanLocal(TimeText.local(order.getPlanStartTime(), TimeText.pickZone(order.getPlanZone(), zone)));
        vo.setActualFinishTime(TimeText.iso(order.getActualFinishTime()));
        vo.setFinishTz(order.getFinishZone());
        vo.setFinishLocal(TimeText.local(order.getActualFinishTime(),
                TimeText.pickZone(order.getFinishZone(), zone)));
        vo.setStatus(order.getStatus());
        vo.setStatusName(Dict.workOrderStatusName(order.getStatus()));
        String next = Dict.nextOrderStatus(order.getStatus());
        vo.setNextStatus(next);
        vo.setNextStatusName(next == null ? null : Dict.workOrderStatusName(next));
        vo.setAcceptResult(order.getAcceptResult());
        vo.setAcceptResultName(Dict.acceptResultName(order.getAcceptResult()));
        vo.setAcceptPerson(order.getAcceptPerson());
        vo.setAcceptTime(TimeText.iso(order.getAcceptTime()));
        vo.setAcceptTz(order.getAcceptZone());
        vo.setAcceptLocal(TimeText.local(order.getAcceptTime(),
                TimeText.pickZone(order.getAcceptZone(), zone)));
        vo.setAcceptNote(order.getAcceptNote());
        vo.setTicket(ticketService.briefOf(ticketService.getByOrder(order.getId())));
        vo.setFaultDesc(order.getFaultDesc());
        vo.setNote(order.getNote());

        List<WorkItem> items = items(order.getId());
        int done = 0;
        for (WorkItem item : items) {
            if (item.getDone() != null && item.getDone()) {
                done += 1;
            }
            vo.getItems().add(toItemVO(item));
        }
        vo.setItemTotal(items.size());
        vo.setItemDone(done);
        vo.setProgress(items.isEmpty() ? 0D : done * 1D / items.size());
        return vo;
    }

    public WorkItemVO toItemVO(WorkItem item) {
        WorkItemVO vo = new WorkItemVO();
        vo.setId(item.getId());
        vo.setWorkOrderId(item.getWorkOrderId());
        vo.setItemSeq(item.getItemSeq());
        vo.setItemName(item.getItemName());
        vo.setStandard(item.getStandard());
        vo.setResult(item.getResult());
        vo.setResultName(Dict.itemResultName(item.getResult()));
        vo.setResultNote(item.getResultNote());
        vo.setDone(item.getDone());
        vo.setNote(item.getNote());
        return vo;
    }

    private static <T> void ifNotNull(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}
