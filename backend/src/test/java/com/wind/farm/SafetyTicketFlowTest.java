package com.wind.farm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wind.farm.common.BizException;
import com.wind.farm.domain.IsolationTemplate;
import com.wind.farm.domain.SafetyTicket;
import com.wind.farm.domain.Turbine;
import com.wind.farm.domain.WindFarm;
import com.wind.farm.domain.WorkOrder;
import com.wind.farm.dto.AcceptanceRequest;
import com.wind.farm.dto.IsolationConfirmRequest;
import com.wind.farm.dto.IsolationPointRequest;
import com.wind.farm.dto.IsolationTemplateRequest;
import com.wind.farm.dto.RunLogRequest;
import com.wind.farm.dto.TicketCreateRequest;
import com.wind.farm.dto.WorkItemRequest;
import com.wind.farm.dto.WorkOrderRequest;
import com.wind.farm.repository.IsolationPointRepository;
import com.wind.farm.repository.IsolationTemplateRepository;
import com.wind.farm.repository.SafetyTicketRepository;
import com.wind.farm.repository.TurbineRepository;
import com.wind.farm.repository.WindFarmRepository;
import com.wind.farm.service.RunLogService;
import com.wind.farm.service.SafetyTicketService;
import com.wind.farm.service.TurbineService;
import com.wind.farm.service.WorkOrderService;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 安全隔离票闭环的全流程集成测试（H2 MySQL 模式 + JPA 自动建表）：
 * 覆盖五态推进、工单 / 机位 / 运行日志三联动闸门、双人确认幂等、严格逆序复电、
 * 同一机位唯一活动票、模板快照与新增风险、隔离失效回退等核心规则。
 */
@SpringBootTest
class SafetyTicketFlowTest {

    @Autowired private SafetyTicketService tickets;
    @Autowired private WorkOrderService orders;
    @Autowired private RunLogService runLogs;
    @Autowired private TurbineService turbineService;
    @Autowired private IsolationTemplateRepository tplRepository;
    @Autowired private IsolationPointRepository pointRepository;
    @Autowired private SafetyTicketRepository ticketRepository;
    @Autowired private TurbineRepository turbineRepository;
    @Autowired private WindFarmRepository farmRepository;
    @Autowired private com.wind.farm.repository.IsolationLogRepository logRepository;
    @Autowired private com.wind.farm.repository.WorkOrderRepository orderRepository;
    @Autowired private com.wind.farm.repository.WorkItemRepository workItemRepository;
    @Autowired private com.wind.farm.repository.RunLogRepository runLogRepository;
    @Autowired private com.wind.farm.repository.PartReplacementRepository replacementRepository;
    @Autowired private com.wind.farm.repository.SparePartRepository sparePartRepository;

    private Long turbineId;

    @BeforeEach
    void setUp() {
        // 单个 Spring 上下文跨用例复用，手动清空全部表（实体未建外键，顺序按依赖关系即可）
        logRepository.deleteAll();
        pointRepository.deleteAll();
        ticketRepository.deleteAll();
        tplRepository.deleteAll();
        workItemRepository.deleteAll();
        replacementRepository.deleteAll();
        orderRepository.deleteAll();
        runLogRepository.deleteAll();
        sparePartRepository.deleteAll();
        turbineRepository.deleteAll();
        farmRepository.deleteAll();

        WindFarm farm = new WindFarm();
        farm.setFarmCode("F-TEST-" + System.nanoTime());
        farm.setFarmName("测试风场");
        farm.setTimeZone("Asia/Shanghai");
        farm.setStatus("ACTIVE");
        Long farmId = farmRepository.save(farm).getId();

        Turbine turbine = new Turbine();
        turbine.setFarmId(farmId);
        turbine.setUnitCode("T1");
        turbine.setUnitName("1号机位");
        turbine.setRatedPowerKw(new BigDecimal("3000"));
        turbine.setStatus("ONLINE");
        turbine.setLastPowerKw(BigDecimal.ZERO);
        turbine.setTodayEnergyKwh(BigDecimal.ZERO);
        turbine.setTotalEnergyKwh(BigDecimal.ZERO);
        turbine.setTotalHours(BigDecimal.ZERO);
        turbineId = turbineRepository.save(turbine).getId();
    }

    private WorkOrder createOrder(String no, String maintType) {
        WorkOrderRequest req = new WorkOrderRequest();
        req.setOrderNo(no);
        req.setTurbineId(turbineId);
        req.setMaintType(maintType);
        req.setTitle("测试工单-" + no);
        req.setCrew("检修一班");
        return orders.create(req);
    }

    private IsolationTemplate tpl(String type, int seq, String name, String kind) {
        IsolationTemplate t = new IsolationTemplate();
        t.setMaintType(type);
        t.setPointSeq(seq);
        t.setPointName(name);
        t.setPointType(kind);
        t.setStandard("标准-" + seq);
        t.setEnabled(true);
        t.setVersion(1);
        return tplRepository.save(t);
    }

    private IsolationConfirmRequest twoPeople(String op, String ver) {
        IsolationConfirmRequest r = new IsolationConfirmRequest();
        r.setOperator(op);
        r.setVerifier(ver);
        return r;
    }

    private IsolationConfirmRequest onePerson(String op) {
        IsolationConfirmRequest r = new IsolationConfirmRequest();
        r.setOperator(op);
        return r;
    }

    private SafetyTicket createTicket(Long orderId, String owner) {
        TicketCreateRequest req = new TicketCreateRequest();
        req.setWorkOrderId(orderId);
        req.setOwner(owner);
        return tickets.createTicket(req);
    }

    private void isolateAll(Long ticketId, String op, String ver) {
        var points = pointRepository.findByTicketIdOrderByPointSeqAsc(ticketId);
        for (var p : points) {
            tickets.confirmPoint(p.getId(), twoPeople(op, ver));
        }
        tickets.completeIsolation(ticketId);
    }

    private void addResultedItem(Long orderId, String name) {
        WorkItemRequest ir = new WorkItemRequest();
        ir.setWorkOrderId(orderId);
        ir.setItemName(name);
        ir.setStandard("标准");
        ir.setResult("PASS");
        ir.setDone(true);
        orders.createItem(ir);
    }

    private void assertBiz(String msgContains, Runnable action) {
        BizException ex = assertThrows(BizException.class, action::run);
        assertTrue(ex.getMessage().contains(msgContains),
                "期望提示包含「" + msgContains + "」，实际：" + ex.getMessage());
    }

    private Long firstPoint(Long ticketId) {
        return pointRepository.findByTicketIdOrderByPointSeqAsc(ticketId).get(0).getId();
    }

    private AcceptanceRequest acceptance(String result, String person) {
        AcceptanceRequest a = new AcceptanceRequest();
        a.setResult(result);
        a.setPerson(person);
        a.setNote("验收意见");
        return a;
    }

    // ---------------- 1. 完整正向闭环 ----------------

    @Test
    void fullHappyPath() {
        tpl("ROUTINE", 1, "停机", "SHUTDOWN");
        tpl("ROUTINE", 2, "停电", "POWER_OFF");
        tpl("ROUTINE", 3, "验电", "VOLTAGE_TEST");
        WorkOrder order0 = createOrder("WO-HAPPY-1", "ROUTINE");
        final Long orderId = order0.getId();

        // 无票不能作业
        assertBiz("还没有安全隔离票", () -> orders.changeStatus(orderId, "WORKING"));

        SafetyTicket ticket0 = createTicket(orderId, "张三");
        final Long ticketId = ticket0.getId();
        assertEquals("DRAFT", ticket0.getStatus());
        assertEquals(3, pointRepository.findByTicketIdOrderByPointSeqAsc(ticketId).size());

        // 草稿也不能作业
        assertBiz("未达到「已隔离」", () -> orders.changeStatus(orderId, "WORKING"));

        // 开始确认：机位自动转检修
        tickets.startConfirm(ticketId);
        assertEquals("MAINTAIN", turbineRepository.findById(turbineId).orElseThrow().getStatus());

        // 确认中仍不能作业
        assertBiz("未达到「已隔离」", () -> orders.changeStatus(orderId, "WORKING"));

        // 双人不能是同一人 / 监护人不能为空
        assertBiz("不能为同一人",
                () -> tickets.confirmPoint(firstPoint(ticketId), twoPeople("张三", "张三")));
        assertBiz("双人确认",
                () -> tickets.confirmPoint(firstPoint(ticketId), onePerson("张三")));

        // 未全部确认不能隔离生效
        tickets.confirmPoint(firstPoint(ticketId), twoPeople("张三", "李四"));
        assertBiz("尚未完成双人隔离确认", () -> tickets.completeIsolation(ticketId));

        // 其余确认完成 → 已隔离
        var rest = pointRepository.findByTicketIdOrderByPointSeqAsc(ticketId);
        tickets.confirmPoint(rest.get(1).getId(), twoPeople("张三", "李四"));
        tickets.confirmPoint(rest.get(2).getId(), twoPeople("王五", "李四"));
        SafetyTicket ticket = tickets.completeIsolation(ticketId);
        assertEquals("ISOLATED", ticket.getStatus());

        // 已隔离才能作业
        assertEquals("WORKING", orders.changeStatus(orderId, "WORKING").getStatus());

        // 已隔离期间禁止登记发电读数
        RunLogRequest logReq = new RunLogRequest();
        logReq.setTurbineId(turbineId);
        logReq.setWindSpeedMs(new BigDecimal("8"));
        logReq.setActivePowerKw(new BigDecimal("100"));
        logReq.setDailyEnergyKwh(new BigDecimal("1000"));
        assertBiz("禁止登记发电读数", () -> runLogs.create(logReq));

        // 已隔离期间禁止人工恢复并网
        assertBiz("尚未恢复", () -> turbineService.changeStatus(turbineId, "ONLINE"));

        // 作业项完成 → 待验收
        addResultedItem(orderId, "作业项1");
        assertEquals("CHECKING", orders.changeStatus(orderId, "CHECKING").getStatus());

        // 未验收不能复电
        assertBiz("尚未验收合格", () -> tickets.startRestore(ticketId));

        // 验收合格
        WorkOrder accepted = orders.accept(orderId, acceptance("PASS", "验收人-赵六"));
        assertEquals("CHECKING", accepted.getStatus());
        assertEquals("PASS", accepted.getAcceptResult());

        // 开始复电
        ticket = tickets.startRestore(ticketId);
        assertEquals("RESTORING", ticket.getStatus());

        // 严格逆序：seq1/seq2 必须等 seq3 先解除
        var pts = pointRepository.findByTicketIdOrderByPointSeqAsc(ticketId);
        assertBiz("必须按隔离顺序的逆序", () -> tickets.restorePoint(pts.get(0).getId(), onePerson("张三")));
        assertBiz("必须按隔离顺序的逆序", () -> tickets.restorePoint(pts.get(1).getId(), onePerson("张三")));

        tickets.restorePoint(pts.get(2).getId(), onePerson("张三"));
        // 重复解除：幂等报错，不产生第二次推进
        assertBiz("请勿重复提交", () -> tickets.restorePoint(pts.get(2).getId(), onePerson("张三")));
        // 还有项未解除，不能完成恢复
        assertBiz("未按逆序解除", () -> tickets.completeRestore(ticketId));

        tickets.restorePoint(pts.get(1).getId(), onePerson("张三"));
        tickets.restorePoint(pts.get(0).getId(), onePerson("张三"));
        ticket = tickets.completeRestore(ticketId);
        assertEquals("RESTORED", ticket.getStatus());
        assertNotNull(ticket.getRestoredTime());
        assertNull(ticket.getActiveSlot());

        // 闭环
        WorkOrder closed = orders.changeStatus(orderId, "CLOSED");
        assertEquals("CLOSED", closed.getStatus());
        assertNotNull(closed.getActualFinishTime());

        // 恢复后允许人工并网
        turbineService.changeStatus(turbineId, "ONLINE");
        assertEquals("ONLINE", turbineRepository.findById(turbineId).orElseThrow().getStatus());

        // 变更记录完整
        var vo = tickets.getVO(ticketId);
        assertTrue(vo.getLogs().size() >= 8, "变更记录至少包含建票/确认/隔离/复电/恢复等动作，实际 " + vo.getLogs().size());
        assertEquals(3, vo.getPoints().size());
    }

    // ---------------- 2. 同一机位只能一张未恢复票 ----------------

    @Test
    void singleActiveTicketPerTurbine() {
        tpl("ROUTINE", 1, "停机", "SHUTDOWN");
        tpl("EMERGENCY", 1, "急停", "SHUTDOWN");
        WorkOrder o1 = createOrder("WO-ACT-1", "ROUTINE");
        createTicket(o1.getId(), "张三");

        WorkOrder o2 = createOrder("WO-ACT-2", "EMERGENCY");
        assertBiz("已有一张尚未恢复的隔离票", () -> createTicket(o2.getId(), "李四"));
        assertBiz("一张工单只能有一张隔离票", () -> createTicket(o1.getId(), "王五"));
    }

    // ---------------- 3. 重复确认幂等 ----------------

    @Test
    void duplicateConfirmIsIdempotent() {
        tpl("TEMPORARY", 1, "停机", "SHUTDOWN");
        WorkOrder o = createOrder("WO-IDEM-1", "TEMPORARY");
        SafetyTicket t = createTicket(o.getId(), "张三");
        tickets.startConfirm(t.getId());
        Long pid = firstPoint(t.getId());

        tickets.confirmPoint(pid, twoPeople("张三", "李四"));
        // 重复点击 / 网络重试：只产生一次确认
        assertBiz("请勿重复提交", () -> tickets.confirmPoint(pid, twoPeople("王五", "赵六")));

        var p = pointRepository.findById(pid).orElseThrow();
        assertEquals("张三", p.getIsolateOperator());
        assertEquals("李四", p.getIsolateVerifier());
        assertEquals("CONFIRMING", ticketRepository.findById(t.getId()).orElseThrow().getStatus());
    }

    // ---------------- 4. 模板快照：后续修改不影响旧票，新增风险必须处理 ----------------

    @Test
    void templateSnapshotAndNewRiskImport() {
        IsolationTemplate p1 = tpl("ROUTINE", 1, "停机", "SHUTDOWN");
        WorkOrder o = createOrder("WO-TPL-1", "ROUTINE");
        SafetyTicket t = createTicket(o.getId(), "张三");

        // 修改模板点（版本升 2）：旧票快照不变，仅差异提示
        IsolationTemplateRequest upd = new IsolationTemplateRequest();
        upd.setStandard("新标准-更严格");
        upd.setPointName("停机");
        upd.setPointType("SHUTDOWN");
        tickets.updateTemplate(p1.getId(), upd);

        var vo = tickets.getVO(t.getId());
        assertEquals(1, vo.getChangedTemplateCount(), "应提示模板点有新版本");
        assertEquals("标准-1", vo.getPoints().get(0).getStandard(), "票内快照不应被模板修改覆盖");
        assertEquals(1, vo.getPoints().get(0).getSnapshotVersion());

        // 新增风险点：开始确认前必须处理
        tpl("ROUTINE", 2, "新增-机械锁定", "MECH_LOCK");
        assertBiz("模板在建票后新增了", () -> tickets.startConfirm(t.getId()));
        assertEquals(1, tickets.getVO(t.getId()).getNewTemplatePoints().size());

        // IMPORT：补入本票
        tickets.acknowledgeTemplateRisk(t.getId(), "IMPORT", "张三");
        assertEquals(2, pointRepository.findByTicketIdOrderByPointSeqAsc(t.getId()).size());
        assertEquals(0, tickets.getVO(t.getId()).getNewTemplatePoints().size());

        // 正常推进
        tickets.startConfirm(t.getId());
        isolateAll(t.getId(), "张三", "李四");
        assertEquals("ISOLATED", ticketRepository.findById(t.getId()).orElseThrow().getStatus());
    }

    @Test
    void ignoreRiskIsRecordedAndRePromptsOnFutureAdd() {
        tpl("OVERHAUL", 1, "停机", "SHUTDOWN");
        WorkOrder o = createOrder("WO-TPL-3", "OVERHAUL");
        SafetyTicket t = createTicket(o.getId(), "张三");
        tpl("OVERHAUL", 2, "新风险A", "MECH_LOCK");

        tickets.acknowledgeTemplateRisk(t.getId(), "IGNORE", "责任人-张三");
        assertEquals(0, tickets.getVO(t.getId()).getNewTemplatePoints().size());
        assertTrue(tickets.getVO(t.getId()).getLogs().stream()
                .anyMatch(l -> "TPL_ACK".equals(l.getAction())), "忽略必须留痕");

        // 未来再新增：重新提示，不会被一次忽略掩盖
        tpl("OVERHAUL", 3, "新风险B", "LOCK_OUT");
        assertEquals(1, tickets.getVO(t.getId()).getNewTemplatePoints().size());
    }

    // ---------------- 5. 隔离失效：退回确认中、工单退回、重新确认 ----------------

    @Test
    void invalidIsolationReopensAndBlocksReIsolation() {
        tpl("TEMPORARY", 1, "停机", "SHUTDOWN");
        tpl("TEMPORARY", 2, "验电", "VOLTAGE_TEST");
        WorkOrder o = createOrder("WO-INV-1", "TEMPORARY");
        final Long orderId = o.getId();
        SafetyTicket t = createTicket(orderId, "张三");
        tickets.startConfirm(t.getId());
        isolateAll(t.getId(), "张三", "李四");
        orders.changeStatus(orderId, "WORKING");

        var pts = pointRepository.findByTicketIdOrderByPointSeqAsc(t.getId());
        tickets.markInvalid(pts.get(1).getId(), "李四", "挂锁脱落");

        assertEquals("CONFIRMING", ticketRepository.findById(t.getId()).orElseThrow().getStatus(),
                "失效后票应退回确认中");
        assertEquals("PENDING", orders.get(orderId).getStatus(), "现场作业必须暂停，工单退回待派");

        // 失效点未重确前不能再隔离
        assertBiz("隔离已失效，必须重新双人确认", () -> tickets.completeIsolation(t.getId()));
        tickets.confirmPoint(pts.get(1).getId(), twoPeople("张三", "李四"));
        assertEquals("ISOLATED", tickets.completeIsolation(t.getId()).getStatus());
    }

    // ---------------- 5b. 复电中失效：已解除点重新隔离、全部重确后再复电 ----------------

    @Test
    void invalidDuringRestoreReIsolatesRestoredPoints() {
        tpl("ROUTINE", 1, "停机", "SHUTDOWN");
        tpl("ROUTINE", 2, "停电", "POWER_OFF");
        tpl("ROUTINE", 3, "验电", "VOLTAGE_TEST");
        WorkOrder o = createOrder("WO-INVR-1", "ROUTINE");
        final Long orderId = o.getId();
        SafetyTicket t = createTicket(orderId, "张三");
        tickets.startConfirm(t.getId());
        isolateAll(t.getId(), "张三", "李四");
        orders.changeStatus(orderId, "WORKING");
        addResultedItem(orderId, "项");
        orders.changeStatus(orderId, "CHECKING");
        orders.accept(orderId, acceptance("PASS", "验收人"));
        tickets.startRestore(t.getId());

        var pts = pointRepository.findByTicketIdOrderByPointSeqAsc(t.getId());
        // 已逆序解除 seq3
        tickets.restorePoint(pts.get(2).getId(), onePerson("张三"));
        // 此时复查 seq2 隔离失效
        tickets.markInvalid(pts.get(1).getId(), "李四", "验电异常");

        t = ticketRepository.findById(t.getId()).orElseThrow();
        assertEquals("CONFIRMING", t.getStatus(), "复电中失效应退回确认中");
        final Long ticketIdAfter = t.getId();
        assertNull(t.getIsolatedTime());
        assertEquals("PENDING", orders.get(orderId).getStatus(), "工单应退回待派暂停作业");

        // seq3（此前已复电）与 seq2（失效）都必须重新确认；seq1 保持已隔离
        var reloaded = pointRepository.findByTicketIdOrderByPointSeqAsc(t.getId());
        assertFalse(reloaded.get(2).getRestored(), "已复电的 seq3 应被重新置为未复电");
        assertFalse(reloaded.get(2).getIsolated(), "已复电的 seq3 应重新待隔离确认");
        assertFalse(reloaded.get(1).getIsolated(), "失效点 seq2 应待重新确认");
        assertTrue(reloaded.get(0).getIsolated(), "未受影响的 seq1 保持已隔离");

        // 未全部重确不能再隔离
        assertBiz("尚未完成双人隔离确认", () -> tickets.completeIsolation(ticketIdAfter));

        // 重确 seq2、seq3 后再次隔离生效
        tickets.confirmPoint(reloaded.get(1).getId(), twoPeople("张三", "李四"));
        tickets.confirmPoint(reloaded.get(2).getId(), twoPeople("王五", "李四"));
        assertEquals("ISOLATED", tickets.completeIsolation(t.getId()).getStatus());
    }

    // ---------------- 6. 验收驳回退回作业中 ----------------

    @Test
    void acceptRejectSendsOrderBackToWorking() {
        tpl("ROUTINE", 1, "停机", "SHUTDOWN");
        WorkOrder o = createOrder("WO-ACC-1", "ROUTINE");
        final Long orderId = o.getId();
        SafetyTicket t = createTicket(orderId, "张三");
        tickets.startConfirm(t.getId());
        isolateAll(t.getId(), "张三", "李四");
        orders.changeStatus(orderId, "WORKING");
        addResultedItem(orderId, "作业项");
        orders.changeStatus(orderId, "CHECKING");

        WorkOrder rejected = orders.accept(orderId, acceptance("FAIL", "验收人"));
        assertEquals("WORKING", rejected.getStatus());
        assertEquals("FAIL", rejected.getAcceptResult());

        // 驳回后复电仍被阻止
        assertBiz("尚未验收合格", () -> tickets.startRestore(t.getId()));
        // 只有待验收状态才能登记验收
        assertBiz("只有待验收的工单才能登记验收结论",
                () -> orders.accept(orderId, acceptance("FAIL", "验收人")));
    }

    // ---------------- 7. 草稿/确认中隔离点维护规则 ----------------

    @Test
    void pointMaintenanceRules() {
        tpl("ROUTINE", 1, "停机", "SHUTDOWN");
        WorkOrder o = createOrder("WO-PT-1", "ROUTINE");
        SafetyTicket t = createTicket(o.getId(), "张三");

        IsolationPointRequest add = new IsolationPointRequest();
        add.setTicketId(t.getId());
        add.setPointName("人工补充点");
        add.setPointType("MECH_LOCK");
        var added = tickets.addPoint(add);
        assertEquals(2, pointRepository.findByTicketIdOrderByPointSeqAsc(t.getId()).size());

        tickets.startConfirm(t.getId());
        // 确认中不能删除点
        assertBiz("不能删除", () -> tickets.removePoint(added.getId()));

        var pts = pointRepository.findByTicketIdOrderByPointSeqAsc(t.getId());
        tickets.confirmPoint(pts.get(0).getId(), twoPeople("张三", "李四"));
        // 不能把新点插在已确认点之前（序号 1 已确认，序号 0 属于插队）
        IsolationPointRequest insert = new IsolationPointRequest();
        insert.setTicketId(t.getId());
        insert.setPointSeq(0);
        insert.setPointName("插队点");
        assertBiz("只能追加到队尾", () -> tickets.addPoint(insert));
    }

    // ---------------- 8. 恢复后机位才允许并网 ----------------

    @Test
    void gridGateByTurbineService() {
        tpl("ROUTINE", 1, "停机", "SHUTDOWN");
        WorkOrder o = createOrder("WO-GRID-1", "ROUTINE");
        final Long orderId = o.getId();
        SafetyTicket t = createTicket(orderId, "张三");

        tickets.startConfirm(t.getId());
        assertBiz("尚未恢复", () -> turbineService.changeStatus(turbineId, "ONLINE"));

        isolateAll(t.getId(), "张三", "李四");
        assertBiz("尚未恢复", () -> turbineService.changeStatus(turbineId, "ONLINE"));

        orders.changeStatus(orderId, "WORKING");
        addResultedItem(orderId, "项");
        orders.changeStatus(orderId, "CHECKING");
        orders.accept(orderId, acceptance("PASS", "验收人"));

        tickets.startRestore(t.getId());
        var pts = pointRepository.findByTicketIdOrderByPointSeqAsc(t.getId());
        for (int i = pts.size() - 1; i >= 0; i--) {
            tickets.restorePoint(pts.get(i).getId(), onePerson("张三"));
        }
        tickets.completeRestore(t.getId());

        turbineService.changeStatus(turbineId, "ONLINE");
        assertEquals("ONLINE", turbineRepository.findById(turbineId).orElseThrow().getStatus());
    }

    // ---------------- 9. 有票但未恢复不能闭环 ----------------

    @Test
    void closeBeforeRestoreBlocked() {
        tpl("ROUTINE", 1, "停机", "SHUTDOWN");
        WorkOrder o = createOrder("WO-CLOSE-1", "ROUTINE");
        final Long orderId = o.getId();
        SafetyTicket t = createTicket(orderId, "张三");
        tickets.startConfirm(t.getId());
        isolateAll(t.getId(), "张三", "李四");
        orders.changeStatus(orderId, "WORKING");
        addResultedItem(orderId, "项");
        orders.changeStatus(orderId, "CHECKING");

        // 未复电恢复不能闭环
        assertBiz("未达到「已恢复」", () -> orders.changeStatus(orderId, "CLOSED"));
    }
}
