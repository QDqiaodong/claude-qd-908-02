package com.wind.farm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wind.farm.common.BizException;
import com.wind.farm.domain.GridLimitCase;
import com.wind.farm.domain.GridPowerAllocation;
import com.wind.farm.domain.GridPowerCommand;
import com.wind.farm.domain.RunLog;
import com.wind.farm.domain.Turbine;
import com.wind.farm.domain.WindFarm;
import com.wind.farm.dto.GridAllocationRequest;
import com.wind.farm.dto.GridCaseHandleRequest;
import com.wind.farm.dto.GridCaseReleaseRequest;
import com.wind.farm.dto.GridCaseReviewRequest;
import com.wind.farm.dto.GridCommandActionRequest;
import com.wind.farm.dto.GridCommandRequest;
import com.wind.farm.dto.RunLogRequest;
import com.wind.farm.repository.GridCaseLogRepository;
import com.wind.farm.repository.GridLimitCaseRepository;
import com.wind.farm.repository.GridLimitEventRepository;
import com.wind.farm.repository.GridPowerAllocationRepository;
import com.wind.farm.repository.GridPowerCommandRepository;
import com.wind.farm.repository.RunLogRepository;
import com.wind.farm.repository.TurbineRepository;
import com.wind.farm.repository.WindFarmRepository;
import com.wind.farm.service.GridCommandService;
import com.wind.farm.service.GridLimitService;
import com.wind.farm.service.RunLogService;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 电网限功率指令的分摊、并行覆盖、停运释放、偏差判定与不可抹除重算测试。 */
@SpringBootTest
class GridPowerLimitFlowTest {

    @Autowired private GridCommandService commands;
    @Autowired private GridLimitService limitService;
    @Autowired private RunLogService runLogs;
    @Autowired private GridPowerCommandRepository commandRepository;
    @Autowired private GridPowerAllocationRepository allocationRepository;
    @Autowired private GridLimitEventRepository eventRepository;
    @Autowired private GridLimitCaseRepository caseRepository;
    @Autowired private GridCaseLogRepository caseLogRepository;
    @Autowired private RunLogRepository runLogRepository;
    @Autowired private TurbineRepository turbineRepository;
    @Autowired private WindFarmRepository farmRepository;

    private Long farmId;
    private Long t1;
    private Long t2;
    private Long stopped;

    @BeforeEach
    void setUp() {
        caseLogRepository.deleteAll();
        caseRepository.deleteAll();
        eventRepository.deleteAll();
        allocationRepository.deleteAll();
        commandRepository.deleteAll();
        runLogRepository.deleteAll();
        turbineRepository.deleteAll();
        farmRepository.deleteAll();

        WindFarm farm = new WindFarm();
        farm.setFarmCode("GF-" + System.nanoTime());
        farm.setFarmName("电网限功率测试风场");
        farm.setTimeZone("Asia/Shanghai");
        farm.setStatus("ACTIVE");
        farmId = farmRepository.save(farm).getId();

        t1 = turbine(farmId, "G1", "3000", "ONLINE");
        t2 = turbine(farmId, "G2", "6000", "ONLINE");
        stopped = turbine(farmId, "G3", "3000", "STOPPED");
    }

    @Test
    void proportionalAllocationExcludesStoppedAndKeepsTotalWithinTarget() {
        GridPowerCommand command = createCommand("GL-PROP-1", "PROPORTIONAL", new BigDecimal("6000"));
        commands.confirmAllocation(command.getId(), new GridAllocationRequest());

        List<GridPowerAllocation> rows = allocationRepository.findByCommandIdOrderByUnitCodeAscIdAsc(command.getId());
        Map<Long, BigDecimal> current = new HashMap<>();
        rows.forEach(r -> current.put(r.getTurbineId(), r.getCurrentLimitKw()));
        assertEquals(new BigDecimal("2000.00"), current.get(t1));
        assertEquals(new BigDecimal("4000.00"), current.get(t2));
        assertEquals(new BigDecimal("0.00"), current.get(stopped));
        assertFalse(rows.stream().filter(r -> r.getTurbineId().equals(stopped)).findFirst().orElseThrow().getRunnableFlag());
        assertEquals(new BigDecimal("6000.00"),
                rows.stream().filter(GridPowerAllocation::getRunnableFlag).map(GridPowerAllocation::getCurrentLimitKw)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));

        GridPowerCommand executing = commands.start(command.getId(), action("张三"));
        assertEquals("EXECUTING", executing.getStatus());
    }

    @Test
    void manualAllocationRequiresEveryRunnableTurbineAndAcceptsConfirmedTotal() {
        GridPowerCommand command = createCommand("GL-MAN-1", "MANUAL", new BigDecimal("5000"));

        GridAllocationRequest partial = new GridAllocationRequest();
        partial.setLimits(Map.of(t1, new BigDecimal("3000")));
        BizException ex = assertThrows(BizException.class,
                () -> commands.confirmAllocation(command.getId(), partial));
        assertTrue(ex.getMessage().contains("尚未逐台确认"));

        GridAllocationRequest confirmed = new GridAllocationRequest();
        confirmed.setLimits(Map.of(t1, new BigDecimal("3000"), t2, new BigDecimal("2000")));
        commands.confirmAllocation(command.getId(), confirmed);
        commands.start(command.getId(), action("张三"));
        assertEquals(new BigDecimal("2000.00"),
                allocationRepository.findByCommandIdAndTurbineId(command.getId(), t2).orElseThrow().getCurrentLimitKw());
    }

    @Test
    void overlappingLooserCommandDoesNotLiftStrictOlderLimit() {
        GridPowerCommand strict = createCommand("GL-STRICT", "PROPORTIONAL", new BigDecimal("3000"));
        commands.confirmAllocation(strict.getId(), new GridAllocationRequest());
        commands.start(strict.getId(), action("张三"));

        GridPowerCommand loose = createCommand("GL-LOOSE", "PROPORTIONAL", new BigDecimal("9000"), 200);
        commands.confirmAllocation(loose.getId(), new GridAllocationRequest());
        commands.start(loose.getId(), action("李四"));

        ZonedDateTime now = ZonedDateTime.now();
        Map<Long, BigDecimal> effective = commands.effectiveTurbineLimits(farmId, now);
        assertEquals(new BigDecimal("1000.00"), effective.get(t1));
        assertEquals(new BigDecimal("2000.00"), effective.get(t2));
        assertTrue(commands.toVO(loose).getOverlaps().stream().anyMatch(o -> "GL-STRICT".equals(o.getCommandNo())));

        record(t1, new BigDecimal("1500"), now.minusMinutes(2), now.minusMinutes(1));
        var events = eventRepository.findByTurbineIdAndObservedTimeBetweenOrderByObservedTimeAscIdAsc(
                t1, now.minusMinutes(10), now.plusMinutes(10));
        assertTrue(events.stream().anyMatch(e -> "GL-STRICT".equals(e.getCommandNo())
                && "SINGLE_EXCEED".equals(e.getClassification())));
    }

    @Test
    void persistentExceedCreatesImmutableRevisionAndCaseTracksHandlingReviewRelease() {
        GridPowerCommand command = createCommand("GL-CASE", "PROPORTIONAL", new BigDecimal("3000"));
        commands.confirmAllocation(command.getId(), new GridAllocationRequest());
        commands.start(command.getId(), action("张三"));

        ZonedDateTime t = ZonedDateTime.now();
        record(t1, new BigDecimal("1500"), t.minusMinutes(10), t.minusMinutes(9));
        record(t1, new BigDecimal("1600"), t.minusMinutes(5), t.minusMinutes(4));

        List<GridLimitCase> cases = caseRepository.findByFarmIdOrderByObservedTimeDescIdDesc(farmId);
        assertFalse(cases.isEmpty());
        Long caseId = cases.get(0).getId();
        assertEquals("PERSISTENT_EXCEED", cases.get(0).getClassification());
        Long firstLogId = cases.get(0).getRunLogId();

        RunLog correctionLog = runLogRepository.findById(firstLogId).orElseThrow();
        RunLogRequest correction = new RunLogRequest();
        correction.setTurbineId(t1);
        correction.setRecordTime(correctionLog.getRecordTime());
        correction.setReportTime(correctionLog.getReportTime());
        correction.setWindSpeedMs(new BigDecimal("7"));
        correction.setActivePowerKw(new BigDecimal("800"));
        correction.setDailyEnergyKwh(new BigDecimal("2000"));
        runLogs.update(firstLogId, correction);

        assertTrue(eventRepository.findByRunLogIdOrderByIdDesc(firstLogId).stream()
                .anyMatch(e -> "SUPERSEDED".equals(e.getStatus())));

        GridCaseHandleRequest handle = new GridCaseHandleRequest();
        handle.setAction("下调单机设定值并电话回报调度");
        handle.setOperator("值班员-王五");
        handle.setNote("现场已处置");
        limitService.handleCase(caseId, handle);

        GridCaseReviewRequest review = new GridCaseReviewRequest();
        review.setResult("CONFIRMED");
        review.setOperator("值长-赵六");
        limitService.reviewCase(caseId, review);

        GridCaseReleaseRequest release = new GridCaseReleaseRequest();
        release.setResult("RESOLVED");
        release.setOperator("值长-赵六");
        GridLimitCase released = limitService.releaseCase(caseId, release);
        assertEquals("RELEASED", released.getStatus());
        assertTrue(caseLogRepository.findByCaseIdOrderByIdAsc(caseId).size() >= 4);
    }

    @Test
    void startingAfterTurbineStoppedReleasesQuotaWithoutOccupyingLimit() {
        Turbine turbine = turbineRepository.findById(stopped).orElseThrow();
        turbine.setStatus("ONLINE");
        turbineRepository.save(turbine);
        GridPowerCommand command = createCommand("GL-STOP", "PROPORTIONAL", new BigDecimal("4500"));
        commands.previewAllocation(command.getId());
        commands.confirmAllocation(command.getId(), new GridAllocationRequest());
        commands.start(command.getId(), action("张三"));
        turbine.setStatus("STOPPED");
        turbine.setLastPowerKw(BigDecimal.ZERO);
        turbineRepository.save(turbine);
        commands.refreshExecutionForTurbine(turbine, "测试员", "机位停运");
        Map<Long, BigDecimal> effective = commands.effectiveTurbineLimits(farmId, ZonedDateTime.now());
        assertFalse(effective.containsKey(stopped));
    }

    private Long turbine(Long farmId, String code, String rated, String status) {
        Turbine turbine = new Turbine();
        turbine.setFarmId(farmId);
        turbine.setUnitCode(code);
        turbine.setUnitName(code + "号机位");
        turbine.setRatedPowerKw(new BigDecimal(rated));
        turbine.setStatus(status);
        turbine.setLastPowerKw(BigDecimal.ZERO);
        turbine.setTodayEnergyKwh(BigDecimal.ZERO);
        turbine.setTotalEnergyKwh(BigDecimal.ZERO);
        turbine.setTotalHours(BigDecimal.ZERO);
        return turbineRepository.save(turbine).getId();
    }

    private GridPowerCommand createCommand(String no, String mode, BigDecimal target) {
        return createCommand(no, mode, target, 100);
    }

    private GridPowerCommand createCommand(String no, String mode, BigDecimal target, int priority) {
        GridCommandRequest req = new GridCommandRequest();
        req.setCommandNo(no);
        req.setFarmId(farmId);
        req.setTargetLimitKw(target);
        req.setAllocationMode(mode);
        req.setPriorityRank(priority);
        req.setStartTime(ZonedDateTime.now().minusMinutes(5));
        req.setEndTime(ZonedDateTime.now().plusHours(2));
        req.setReleaseCondition("调度解除通知到达");
        req.setOwner("值班员");
        req.setTurbineIds(List.of(t1, t2, stopped));
        return commands.create(req);
    }

    private GridCommandActionRequest action(String operator) {
        GridCommandActionRequest req = new GridCommandActionRequest();
        req.setOperator(operator);
        req.setReason("测试动作");
        return req;
    }

    private void record(Long turbineId, BigDecimal power, ZonedDateTime recordTime, ZonedDateTime reportTime) {
        RunLogRequest req = new RunLogRequest();
        req.setTurbineId(turbineId);
        req.setRecordTime(recordTime);
        req.setReportTime(reportTime);
        req.setWindSpeedMs(new BigDecimal("8"));
        req.setActivePowerKw(power);
        req.setDailyEnergyKwh(new BigDecimal("1000"));
        runLogs.create(req);
    }
}
