package com.wind.farm.service;

import com.wind.farm.common.BizException;
import com.wind.farm.common.Dict;
import com.wind.farm.common.TimeText;
import com.wind.farm.domain.SafetyTicket;
import com.wind.farm.domain.Turbine;
import com.wind.farm.domain.WindFarm;
import com.wind.farm.dto.TurbineRequest;
import com.wind.farm.dto.Views.TurbineVO;
import com.wind.farm.repository.SafetyTicketRepository;
import com.wind.farm.repository.TurbineRepository;
import com.wind.farm.repository.WindFarmRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 机位（风机）：机位编号在同一风场内唯一、额定功率 > 0、
 * 状态流转 并网 → 检修 / 故障 / 停运 → 并网，故障机位不允许登记发电读数。
 */
@Service
public class TurbineService {

    private final TurbineRepository turbineRepository;
    private final WindFarmRepository farmRepository;
    private final FarmService farmService;
    private final SafetyTicketRepository ticketRepository;
    private final GridCommandService gridCommandService;

    public TurbineService(TurbineRepository turbineRepository, WindFarmRepository farmRepository,
            FarmService farmService, SafetyTicketRepository ticketRepository,
            @Lazy GridCommandService gridCommandService) {
        this.turbineRepository = turbineRepository;
        this.farmRepository = farmRepository;
        this.farmService = farmService;
        this.ticketRepository = ticketRepository;
        this.gridCommandService = gridCommandService;
    }

    public List<Turbine> list(Long farmId) {
        if (farmId == null) {
            return turbineRepository.findAllByOrderByFarmIdAscUnitCodeAsc();
        }
        return turbineRepository.findByFarmIdOrderByUnitCodeAsc(farmId);
    }

    /** 机位大卡片网格：主区导航就是这张网格 */
    public List<TurbineVO> grid(Long farmId) {
        List<TurbineVO> result = new ArrayList<>();
        for (Turbine turbine : list(farmId)) {
            result.add(toVO(turbine));
        }
        return result;
    }

    public Turbine get(Long id) {
        return turbineRepository.findById(id).orElseThrow(() -> new BizException("机位不存在：" + id));
    }

    @Transactional
    public Turbine create(TurbineRequest request) {
        if (request.getFarmId() == null) {
            throw new BizException("请选择机位所属风场");
        }
        WindFarm farm = farmRepository.findById(request.getFarmId())
                .orElseThrow(() -> new BizException("风场不存在：" + request.getFarmId()));
        if ("STOPPED".equals(farm.getStatus())) {
            throw new BizException("风场 " + farm.getFarmCode() + " 已停运，不允许新增机位");
        }
        if (!StringUtils.hasText(request.getUnitCode())) {
            throw new BizException("机位编号不能为空");
        }
        if (turbineRepository.existsByFarmIdAndUnitCode(request.getFarmId(), request.getUnitCode())) {
            throw new BizException("风场 " + farm.getFarmCode() + " 下已存在机位编号：" + request.getUnitCode());
        }
        if (request.getRatedPowerKw() == null || request.getRatedPowerKw().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("额定功率必须大于 0");
        }
        if (request.getRatedPowerKw().compareTo(Dict.MAX_RATED_POWER_KW) > 0) {
            throw new BizException("额定功率不得超过 " + Dict.MAX_RATED_POWER_KW + " kW");
        }
        if (request.getStatus() != null && !Dict.isValidTurbineStatus(request.getStatus())) {
            throw new BizException("机位状态非法：" + request.getStatus());
        }
        Turbine turbine = new Turbine();
        turbine.setFarmId(farm.getId());
        turbine.setUnitCode(request.getUnitCode());
        turbine.setUnitName(request.getUnitName());
        turbine.setModel(request.getModel());
        turbine.setRatedPowerKw(request.getRatedPowerKw());
        turbine.setHubHeightM(request.getHubHeightM());
        turbine.setRotorDiameterM(request.getRotorDiameterM());
        turbine.setInstallDate(request.getInstallDate());
        turbine.setStatus(request.getStatus());
        turbine.setTotalEnergyKwh(request.getTotalEnergyKwh());
        turbine.setTotalHours(request.getTotalHours());
        turbine.setNote(request.getNote());
        // 默认值写在 create 里，实体字段不带默认值
        if (turbine.getStatus() == null) {
            turbine.setStatus("ONLINE");
        }
        if (turbine.getLastPowerKw() == null) {
            turbine.setLastPowerKw(BigDecimal.ZERO);
        }
        if (turbine.getTodayEnergyKwh() == null) {
            turbine.setTodayEnergyKwh(BigDecimal.ZERO);
        }
        if (turbine.getTotalEnergyKwh() == null) {
            turbine.setTotalEnergyKwh(BigDecimal.ZERO);
        }
        if (turbine.getTotalHours() == null) {
            turbine.setTotalHours(BigDecimal.ZERO);
        }
        if (turbine.getInstallDate() == null) {
            turbine.setInstallDate(LocalDate.now());
        }
        Turbine saved = turbineRepository.save(turbine);
        farmService.refreshTurbineCount(farm.getId());
        return saved;
    }

    @Transactional
    public Turbine update(Long id, TurbineRequest request) {
        Turbine turbine = get(id);
        if (StringUtils.hasText(request.getUnitCode())
                && !request.getUnitCode().equals(turbine.getUnitCode())
                && turbineRepository.existsByFarmIdAndUnitCode(turbine.getFarmId(), request.getUnitCode())) {
            throw new BizException("该风场下已存在机位编号：" + request.getUnitCode());
        }
        if (request.getRatedPowerKw() != null) {
            if (request.getRatedPowerKw().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BizException("额定功率必须大于 0");
            }
            if (request.getRatedPowerKw().compareTo(Dict.MAX_RATED_POWER_KW) > 0) {
                throw new BizException("额定功率不得超过 " + Dict.MAX_RATED_POWER_KW + " kW");
            }
        }
        if (request.getStatus() != null && !Dict.isValidTurbineStatus(request.getStatus())) {
            throw new BizException("机位状态非法：" + request.getStatus());
        }
        ifNotNull(request.getUnitCode(), turbine::setUnitCode);
        ifNotNull(request.getUnitName(), turbine::setUnitName);
        ifNotNull(request.getModel(), turbine::setModel);
        ifNotNull(request.getRatedPowerKw(), turbine::setRatedPowerKw);
        ifNotNull(request.getHubHeightM(), turbine::setHubHeightM);
        ifNotNull(request.getRotorDiameterM(), turbine::setRotorDiameterM);
        ifNotNull(request.getInstallDate(), turbine::setInstallDate);
        ifNotNull(request.getStatus(), turbine::setStatus);
        ifNotNull(request.getTotalEnergyKwh(), turbine::setTotalEnergyKwh);
        ifNotNull(request.getTotalHours(), turbine::setTotalHours);
        ifNotNull(request.getNote(), turbine::setNote);
        return turbineRepository.save(turbine);
    }

    /** 机位状态流转：并网 ⇄ 检修 / 故障 / 停运，非法流转一律拦截 */
    @Transactional
    public Turbine changeStatus(Long id, String status) {
        if (!Dict.isValidTurbineStatus(status)) {
            throw new BizException("机位状态只能为 并网 / 检修 / 故障 / 停运：" + status);
        }
        Turbine turbine = get(id);
        if (!Dict.isValidTurbineFlow(turbine.getStatus(), status)) {
            throw new BizException("机位 " + turbine.getUnitCode() + " 不允许从 " + Dict.turbineStatusName(turbine.getStatus())
                    + " 直接流转到 " + Dict.turbineStatusName(status) + "；" + Dict.turbineFlowHint(turbine.getStatus()));
        }
        // 隔离联动闸门：机位存在尚未恢复的隔离票时，一律不允许恢复并网（哪怕票已隔离、作业还没验收完）
        if ("ONLINE".equals(status)) {
            SafetyTicket active = ticketRepository.findByTurbineIdAndActiveSlot(id, 1L).orElse(null);
            if (active != null) {
                throw new BizException("机位 " + turbine.getUnitCode() + " 的安全隔离票 " + active.getTicketNo()
                        + " 尚未恢复（当前「" + Dict.ticketStatusName(active.getStatus())
                        + "」），不能恢复并网；请先在工单上完成验收并按逆序复电恢复");
            }
        }
        turbine.setStatus(status);
        if (!"ONLINE".equals(status)) {
            turbine.setLastPowerKw(BigDecimal.ZERO);
        }
        Turbine saved = turbineRepository.save(turbine);
        gridCommandService.refreshExecutionForTurbine(saved, "值班人员",
                "机位状态变更为「" + Dict.turbineStatusName(status) + "」，刷新限功率分摊额度");
        return saved;
    }

    @Transactional
    public void remove(Long id) {
        Turbine turbine = get(id);
        if (turbine.getTotalEnergyKwh() != null && turbine.getTotalEnergyKwh().compareTo(BigDecimal.ZERO) > 0) {
            throw new BizException("机位已有累计发电量记录，不允许删除");
        }
        Long farmId = turbine.getFarmId();
        turbineRepository.delete(turbine);
        farmService.refreshTurbineCount(farmId);
    }

    public TurbineVO toVO(Turbine turbine) {
        TurbineVO vo = new TurbineVO();
        vo.setId(turbine.getId());
        vo.setFarmId(turbine.getFarmId());
        WindFarm farm = turbine.getFarmId() == null ? null : farmRepository.findById(turbine.getFarmId()).orElse(null);
        vo.setFarmCode(farm == null ? null : farm.getFarmCode());
        vo.setFarmName(farm == null ? null : farm.getFarmName());
        vo.setTimeZone(farm == null ? null : farm.getTimeZone());
        vo.setTimeZoneName(farm == null ? null : Dict.timeZoneName(farm.getTimeZone()));
        vo.setOffsetText(farm == null ? null : Dict.zoneOffsetText(farm.getTimeZone()));
        vo.setUnitCode(turbine.getUnitCode());
        vo.setUnitName(turbine.getUnitName());
        vo.setModel(turbine.getModel());
        vo.setRatedPowerKw(turbine.getRatedPowerKw());
        vo.setHubHeightM(turbine.getHubHeightM());
        vo.setRotorDiameterM(turbine.getRotorDiameterM());
        vo.setInstallDate(TimeText.date(turbine.getInstallDate()));
        vo.setStatus(turbine.getStatus());
        vo.setStatusName(turbine.getStatusName());
        vo.setStatusColor(turbine.getStatusColor());
        vo.setLastPowerKw(turbine.getLastPowerKw());
        vo.setTodayEnergyKwh(turbine.getTodayEnergyKwh());
        vo.setTotalEnergyKwh(turbine.getTotalEnergyKwh());
        vo.setTotalHours(turbine.getTotalHours());
        vo.setLoadRate(turbine.getLoadRate());
        vo.setCanRecord(!turbine.isFault());
        // 隔离票锁定信息：未恢复票存在时禁止并网，确认中 / 已隔离 / 复电中还禁止登记发电读数
        SafetyTicket activeTicket = ticketRepository.findByTurbineIdAndActiveSlot(turbine.getId(), 1L).orElse(null);
        boolean locked = activeTicket != null && Dict.isTurbineLockedByTicket(activeTicket.getStatus());
        vo.setIsolationLocked(locked);
        if (activeTicket != null) {
            vo.setActiveTicketNo(activeTicket.getTicketNo());
            vo.setActiveTicketOwner(activeTicket.getOwner());
            vo.setActiveTicketStatus(activeTicket.getStatus());
            vo.setActiveTicketStatusName(Dict.ticketStatusName(activeTicket.getStatus()));
            if (locked) {
                vo.setCanRecord(false);
            }
        }
        vo.setNote(turbine.getNote());
        return vo;
    }

    private static <T> void ifNotNull(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}
