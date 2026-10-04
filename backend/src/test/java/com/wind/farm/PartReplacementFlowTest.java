package com.wind.farm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wind.farm.common.BizException;
import com.wind.farm.common.Dict;
import com.wind.farm.domain.PartReplacement;
import com.wind.farm.domain.SparePart;
import com.wind.farm.domain.Turbine;
import com.wind.farm.domain.WindFarm;
import com.wind.farm.domain.WorkOrder;
import com.wind.farm.dto.ReplacementRequest;
import com.wind.farm.dto.ReplacementResult;
import com.wind.farm.dto.SparePartRequest;
import com.wind.farm.dto.Views.ReplacementVO;
import com.wind.farm.dto.WorkOrderRequest;
import com.wind.farm.repository.PartReplacementRepository;
import com.wind.farm.repository.SparePartRepository;
import com.wind.farm.repository.TurbineRepository;
import com.wind.farm.repository.WindFarmRepository;
import com.wind.farm.repository.WorkOrderRepository;
import com.wind.farm.service.SparePartService;
import com.wind.farm.service.WorkOrderService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 备件更换登记关联写入链的集成测试（H2 MySQL 模式 + JPA 自动建表）：
 * 覆盖机位 / 工单一致性、只选工单补齐机位、工单状态与库存的提交时复核、
 * 并发不扣负、令牌 / 自然键幂等、旧请求兼容、冲突旧数据待核对等核心规则。
 */
@SpringBootTest
class PartReplacementFlowTest {

    @Autowired private SparePartService parts;
    @Autowired private WorkOrderService orders;
    @Autowired private SparePartRepository partRepository;
    @Autowired private PartReplacementRepository replacementRepository;
    @Autowired private WorkOrderRepository orderRepository;
    @Autowired private TurbineRepository turbineRepository;
    @Autowired private WindFarmRepository farmRepository;

    private Long farmId;
    private Long turbineAId;
    private Long turbineBId;

    @BeforeEach
    void setUp() {
        replacementRepository.deleteAll();
        orderRepository.deleteAll();
        partRepository.deleteAll();
        turbineRepository.deleteAll();
        farmRepository.deleteAll();

        WindFarm farm = new WindFarm();
        farm.setFarmCode("F-PRT-" + System.nanoTime());
        farm.setFarmName("测试风场");
        farm.setTimeZone("Asia/Shanghai");
        farm.setStatus("ACTIVE");
        farmId = farmRepository.save(farm).getId();
        turbineAId = saveTurbine(farmId, "A01", "1号机位");
        turbineBId = saveTurbine(farmId, "B02", "2号机位");
    }

    private Long saveTurbine(Long fid, String code, String name) {
        Turbine t = new Turbine();
        t.setFarmId(fid);
        t.setUnitCode(code);
        t.setUnitName(name);
        t.setRatedPowerKw(new BigDecimal("3000"));
        t.setStatus("MAINTAIN");
        return turbineRepository.save(t).getId();
    }

    private Long createPart(String code, int stock, int safety) {
        SparePartRequest req = new SparePartRequest();
        req.setPartCode(code);
        req.setPartName("测试备件-" + code);
        req.setCategory("ELECTRIC");
        req.setUnit("件");
        req.setStockQty(stock);
        req.setSafetyStock(safety);
        return parts.create(req).getId();
    }

    private Long createOrder(String no, Long turbineId, String status) {
        WorkOrderRequest req = new WorkOrderRequest();
        req.setOrderNo(no);
        req.setTurbineId(turbineId);
        req.setMaintType("TEMPORARY");
        req.setTitle("测试工单-" + no);
        req.setCrew("检修一班");
        // 一律先以待派创建（避开隔离票作业前置——本测试只关注备件更换链），
        // 再按用例需要把状态直接置为目标值；备件服务只读状态、不感知推进过程。
        WorkOrder o = orders.create(req);
        if (!status.equals(o.getStatus())) {
            o.setStatus(status);
            orderRepository.save(o);
        }
        return o.getId();
    }

    private ReplacementRequest request(Long partId, Long turbineId, Long orderId, int qty, String token) {
        ReplacementRequest r = new ReplacementRequest();
        r.setPartId(partId);
        r.setTurbineId(turbineId);
        r.setWorkOrderId(orderId);
        r.setQty(qty);
        r.setOperator("张三");
        r.setClientToken(token);
        return r;
    }

    private int stock(Long partId) {
        return partRepository.findById(partId).orElseThrow().getStockQty();
    }

    private void assertBiz(String msgContains, Runnable action) {
        BizException ex = assertThrows(BizException.class, action::run);
        assertTrue(ex.getMessage().contains(msgContains),
                "期望提示包含「" + msgContains + "」，实际：" + ex.getMessage());
    }

    // 1. 机位与工单不属于同一台机组：冲突必须拒绝，且不扣减、不落履历

    @Test
    void mismatchedTurbineAndOrderRejectedWithoutSideEffect() {
        Long partId = createPart("SP-MISMATCH-1", 5, 1);
        Long orderId = createOrder("WO-MISMATCH-1", turbineAId, "WORKING");

        ReplacementRequest r = request(partId, turbineBId, orderId, 1, UUID.randomUUID().toString());
        assertBiz("不属于同一台机组", () -> parts.replace(r));

        assertEquals(5, stock(partId), "冲突拒绝不能扣库存");
        assertTrue(replacementRepository.findAll().isEmpty(), "冲突拒绝不能生成更换记录");
    }

    // 2. 只选工单：由工单补齐机位，履历写工单真实所属机位

    @Test
    void orderOnlyFillsTurbineFromOrder() {
        Long partId = createPart("SP-FILL-1", 5, 1);
        Long orderId = createOrder("WO-FILL-1", turbineAId, "WORKING");

        ReplacementRequest r = request(partId, null, orderId, 1, UUID.randomUUID().toString());
        ReplacementResult result = parts.replace(r);

        assertTrue(result.isTurbineFilledFromOrder());
        assertFalse(result.isDuplicated());
        PartReplacement rec = result.getRecord();
        assertEquals(turbineAId, rec.getTurbineId());
        assertEquals("A01", rec.getUnitCode());
        assertEquals(4, stock(partId));
        assertEquals(1, replacementRepository.count());
    }

    // 3. 机位与工单一致：正常登记

    @Test
    void matchedTurbineAndOrderSucceeds() {
        Long partId = createPart("SP-MATCH-1", 5, 1);
        Long orderId = createOrder("WO-MATCH-1", turbineAId, "WORKING");

        ReplacementResult result = parts.replace(request(partId, turbineAId, orderId, 2, UUID.randomUUID().toString()));
        assertFalse(result.isDuplicated());
        assertEquals(3, stock(partId));
        assertEquals(1, replacementRepository.count());
    }

    // 4. 提交时复核工单状态：待派 / 待验收 / 已闭环都不能登记，且不扣减

    @Test
    void nonWorkingOrderRejected() {
        Long partId = createPart("SP-STATUS-1", 5, 1);
        for (String status : new String[]{"PENDING", "CHECKING", "CLOSED"}) {
            String no = "WO-STATUS-" + status;
            Long orderId = createOrder(no, turbineAId, status);
            ReplacementRequest r = request(partId, null, orderId, 1, UUID.randomUUID().toString());
            assertBiz("不是作业中", () -> parts.replace(r));
        }
        assertEquals(5, stock(partId));
        assertEquals(0, replacementRepository.count());
    }

    // 5. 数量超过当前库存：拒绝、不扣减、不落履历

    @Test
    void insufficientStockRejected() {
        Long partId = createPart("SP-STOCK-1", 2, 1);
        Long orderId = createOrder("WO-STOCK-1", turbineAId, "WORKING");

        assertBiz("当前库存仅 2",
                () -> parts.replace(request(partId, turbineAId, orderId, 3, UUID.randomUUID().toString())));
        assertEquals(2, stock(partId));
        assertEquals(0, replacementRepository.count());
    }

    // 6. 同一令牌重复提交（网络重试 / 重复点击）：只扣一次、只有一条履历

    @Test
    void sameClientTokenIsIdempotent() {
        Long partId = createPart("SP-TOKEN-1", 5, 1);
        Long orderId = createOrder("WO-TOKEN-1", turbineAId, "WORKING");
        String token = UUID.randomUUID().toString();

        ReplacementResult first = parts.replace(request(partId, turbineAId, orderId, 1, token));
        ReplacementResult retry = parts.replace(request(partId, turbineAId, orderId, 1, token));

        assertFalse(first.isDuplicated());
        assertTrue(retry.isDuplicated());
        assertEquals(4, stock(partId), "重试不能重复扣减");
        assertEquals(1, replacementRepository.count(), "同一次更换只能留一条有效履历");
        assertEquals(first.getRecord().getId(), retry.getRecord().getId());
    }

    // 7. 旧客户端无令牌：自然键短窗内重试幂等；窗口后正常连续登记不被误并

    @Test
    void legacyRequestNaturalDedupWindow() {
        Long partId = createPart("SP-LEGACY-1", 10, 1);
        Long orderId = createOrder("WO-LEGACY-1", turbineAId, "WORKING");

        ReplacementResult first = parts.replace(request(partId, turbineAId, orderId, 1, null));
        assertEquals(Dict.SOURCE_LEGACY_BOTH, first.getRecord().getSource());
        ReplacementResult retry = parts.replace(request(partId, turbineAId, orderId, 1, null));
        assertTrue(retry.isDuplicated(), "旧请求短窗内同内容重试应幂等");
        assertEquals(9, stock(partId));
        assertEquals(1, replacementRepository.count());
    }

    @Test
    void legacyOrderOnlyClassifiedAndFilled() {
        Long partId = createPart("SP-LEGACY-2", 10, 1);
        Long orderId = createOrder("WO-LEGACY-2", turbineAId, "WORKING");

        ReplacementResult r = parts.replace(request(partId, null, orderId, 1, null));
        assertEquals(Dict.SOURCE_LEGACY_ORDER_ONLY, r.getRecord().getSource());
        assertEquals(turbineAId, r.getRecord().getTurbineId());
    }

    @Test
    void legacyTurbineOnlyAndNoneStillAllowed() {
        Long partId = createPart("SP-LEGACY-3", 10, 1);
        // 只带机位：兼容
        ReplacementResult onlyTurbine = parts.replace(request(partId, turbineAId, null, 1, null));
        assertEquals(Dict.SOURCE_LEGACY_TURBINE_ONLY, onlyTurbine.getRecord().getSource());
        // 两者都不带：兼容（不关联工单，不会串履历）
        ReplacementResult none = parts.replace(request(partId, null, null, 1, null));
        assertEquals(Dict.SOURCE_LEGACY_NONE, none.getRecord().getSource());
        assertNull(none.getRecord().getWorkOrderId());
        assertNull(none.getRecord().getTurbineId());
        assertEquals(8, stock(partId));
        assertEquals(2, replacementRepository.count());
    }

    // 8. 并发：两人同时把仅剩库存领走，只有一笔成功，库存不为负、履历不重复

    @Test
    void concurrentDeductionNeverGoesNegativeAndOneRecord() throws Exception {
        Long partId = createPart("SP-CONC-1", 1, 0);
        Long orderId = createOrder("WO-CONC-1", turbineAId, "WORKING");

        RunnableTask t1 = new RunnableTask(parts, request(partId, turbineAId, orderId, 1, UUID.randomUUID().toString()));
        RunnableTask t2 = new RunnableTask(parts, request(partId, turbineAId, orderId, 1, UUID.randomUUID().toString()));
        Thread a = new Thread(t1);
        Thread b = new Thread(t2);
        a.start();
        b.start();
        a.join();
        b.join();

        int success = (t1.success ? 1 : 0) + (t2.success ? 1 : 0);
        assertEquals(1, success, "并发下只能有一笔成功，另一笔必须提示库存不足");
        assertNotNull(t1.error != null ? t1.error : t2.error, "失败方应拿到明确的库存变化提示");
        assertEquals(0, stock(partId), "库存扣到 0 但绝不为负");
        assertEquals(1, replacementRepository.count(), "只留一条有效履历");
    }

    // 9. 冲突旧数据（机位与工单归属不一致）显示为待核对，且不被静默改写

    @Test
    void conflictingHistoryShownAsPendingCheck() {
        Long partId = createPart("SP-CHK-1", 10, 1);
        Long orderA = createOrder("WO-CHK-1", turbineAId, "WORKING");

        // 手工造一条历史冲突记录：工单属于 A，履历却写 B（旧链路串机位的数据）
        PartReplacement bad = new PartReplacement();
        SparePart part = partRepository.findById(partId).orElseThrow();
        bad.setPartId(partId);
        bad.setPartCode(part.getPartCode());
        bad.setPartName(part.getPartName());
        bad.setTurbineId(turbineBId);
        bad.setUnitCode("B02");
        bad.setWorkOrderId(orderA);
        bad.setOrderNo(orderRepository.findById(orderA).orElseThrow().getOrderNo());
        bad.setQty(1);
        bad.setReplaceTime(java.time.LocalDateTime.now().minusHours(1));
        bad.setOperator("旧客户端");
        bad.setStockAfter(9);
        bad.setWarningFlag(false);
        replacementRepository.save(bad);

        // 正常的一条
        parts.replace(request(partId, turbineAId, orderA, 1, UUID.randomUUID().toString()));

        java.util.List<ReplacementVO> vos = parts.replacementVOs(null, null);
        ReplacementVO pending = vos.stream().filter(ReplacementVO::getPendingCheck).findFirst().orElse(null);
        assertNotNull(pending, "冲突旧记录必须显示为待核对");
        assertTrue(pending.getCheckNote().contains("不一致"), "待核对要说明原因：" + pending.getCheckNote());
        assertEquals("A01", pending.getActualOrderUnitCode());

        // 原始历史行没有被改写
        PartReplacement raw = replacementRepository.findById(bad.getId()).orElseThrow();
        assertEquals(turbineBId, raw.getTurbineId(), "不能静默改写历史机位");
        assertEquals("B02", raw.getUnitCode());
    }

    // 10. 关联工单被删除：旧记录同样标待核对，正常新登记不受影响（不锁死）

    @Test
    void deletedOrderHistoryPendingButNewRegistrationsWork() {
        Long partId = createPart("SP-CHK-2", 10, 1);
        Long orderA = createOrder("WO-CHK-2", turbineAId, "WORKING");

        PartReplacement old = new PartReplacement();
        SparePart part = partRepository.findById(partId).orElseThrow();
        old.setPartId(partId);
        old.setPartCode(part.getPartCode());
        old.setPartName(part.getPartName());
        old.setTurbineId(turbineAId);
        old.setUnitCode("A01");
        old.setWorkOrderId(orderA);
        old.setOrderNo(orderRepository.findById(orderA).orElseThrow().getOrderNo());
        old.setQty(1);
        old.setReplaceTime(java.time.LocalDateTime.now().minusHours(1));
        old.setOperator("旧客户端");
        old.setStockAfter(9);
        old.setWarningFlag(false);
        replacementRepository.save(old);

        orderRepository.deleteById(orderA);

        ReplacementVO vo = parts.replacementVOs(null, null).stream()
                .filter(v -> v.getId().equals(old.getId())).findFirst().orElseThrow();
        assertTrue(vo.getPendingCheck());
        assertTrue(vo.getCheckNote().contains("工单已删除"));

        // 仍可用仅机位或新工单正常登记，不被历史问题锁死（手工造的旧记录不扣库存，故 10→9）
        parts.replace(request(partId, turbineAId, null, 1, UUID.randomUUID().toString()));
        assertEquals(9, stock(partId));
    }

    static class RunnableTask implements Runnable {
        private final SparePartService service;
        private final ReplacementRequest request;
        volatile boolean success;
        volatile Throwable error;

        RunnableTask(SparePartService service, ReplacementRequest request) {
            this.service = service;
            this.request = request;
        }

        @Override
        public void run() {
            try {
                service.replace(request);
                success = true;
            } catch (Throwable e) {
                success = false;
                error = e;
            }
        }
    }
}
