package com.wind.farm.service;

import com.wind.farm.common.BizException;
import com.wind.farm.common.Dict;
import com.wind.farm.common.TimeText;
import com.wind.farm.domain.WindFarm;
import com.wind.farm.dto.FarmRequest;
import com.wind.farm.dto.Views.FarmVO;
import com.wind.farm.repository.TurbineRepository;
import com.wind.farm.repository.WindFarmRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 风场：风场编号唯一、必须带时区标识、装机容量 > 0。
 * 机位数量由机位增删时回写维护。
 */
@Service
public class FarmService {

    private final WindFarmRepository farmRepository;
    private final TurbineRepository turbineRepository;

    public FarmService(WindFarmRepository farmRepository, TurbineRepository turbineRepository) {
        this.farmRepository = farmRepository;
        this.turbineRepository = turbineRepository;
    }

    public List<WindFarm> list() {
        return farmRepository.findAllByOrderByFarmCodeAsc();
    }

    public List<FarmVO> listVO() {
        List<FarmVO> result = new ArrayList<>();
        for (WindFarm farm : list()) {
            result.add(toVO(farm));
        }
        return result;
    }

    public WindFarm get(Long id) {
        return farmRepository.findById(id).orElseThrow(() -> new BizException("风场不存在：" + id));
    }

    public WindFarm getByCode(String farmCode) {
        return farmRepository.findAllByOrderByFarmCodeAsc().stream()
                .filter(f -> farmCode != null && farmCode.equals(f.getFarmCode()))
                .findFirst()
                .orElseThrow(() -> new BizException("风场不存在：" + farmCode));
    }

    @Transactional
    public WindFarm create(FarmRequest request) {
        if (!StringUtils.hasText(request.getFarmCode())) {
            throw new BizException("风场编号不能为空");
        }
        if (farmRepository.existsByFarmCode(request.getFarmCode())) {
            throw new BizException("风场编号已存在：" + request.getFarmCode());
        }
        if (!StringUtils.hasText(request.getTimeZone())) {
            throw new BizException("风场必须带时区标识，可选：" + String.join(" / ", Dict.TIME_ZONES));
        }
        if (!Dict.isValidTimeZone(request.getTimeZone())) {
            throw new BizException("时区标识非法：" + request.getTimeZone() + "，可选：" + String.join(" / ", Dict.TIME_ZONES));
        }
        if (request.getInstalledCapacityMw() != null
                && request.getInstalledCapacityMw().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("装机容量必须大于 0");
        }
        if (request.getGridDate() != null && request.getGridDate().isAfter(LocalDate.now())) {
            throw new BizException("并网日期不能晚于今天");
        }
        if (request.getStatus() != null && !Dict.isValidFarmStatus(request.getStatus())) {
            throw new BizException("风场状态只能为 在运 / 检修 / 停运：" + request.getStatus());
        }
        WindFarm farm = new WindFarm();
        farm.setFarmCode(request.getFarmCode());
        farm.setFarmName(request.getFarmName());
        farm.setRegion(request.getRegion());
        farm.setProvince(request.getProvince());
        farm.setTimeZone(request.getTimeZone());
        farm.setInstalledCapacityMw(request.getInstalledCapacityMw());
        farm.setGridDate(request.getGridDate());
        farm.setOwner(request.getOwner());
        farm.setStatus(request.getStatus());
        farm.setNote(request.getNote());
        // 默认值写在 create 里，实体字段不带默认值
        if (farm.getStatus() == null) {
            farm.setStatus("ACTIVE");
        }
        if (farm.getTurbineCount() == null) {
            farm.setTurbineCount(0);
        }
        if (farm.getGridDate() == null) {
            farm.setGridDate(LocalDate.now());
        }
        return farmRepository.save(farm);
    }

    @Transactional
    public WindFarm update(Long id, FarmRequest request) {
        WindFarm farm = get(id);
        if (StringUtils.hasText(request.getFarmCode())
                && !request.getFarmCode().equals(farm.getFarmCode())
                && farmRepository.existsByFarmCode(request.getFarmCode())) {
            throw new BizException("风场编号已存在：" + request.getFarmCode());
        }
        if (request.getTimeZone() != null && !Dict.isValidTimeZone(request.getTimeZone())) {
            throw new BizException("时区标识非法：" + request.getTimeZone() + "，可选：" + String.join(" / ", Dict.TIME_ZONES));
        }
        if (request.getInstalledCapacityMw() != null
                && request.getInstalledCapacityMw().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("装机容量必须大于 0");
        }
        if (request.getStatus() != null && !Dict.isValidFarmStatus(request.getStatus())) {
            throw new BizException("风场状态只能为 在运 / 检修 / 停运：" + request.getStatus());
        }
        ifNotNull(request.getFarmCode(), farm::setFarmCode);
        ifNotNull(request.getFarmName(), farm::setFarmName);
        ifNotNull(request.getRegion(), farm::setRegion);
        ifNotNull(request.getProvince(), farm::setProvince);
        ifNotNull(request.getTimeZone(), farm::setTimeZone);
        ifNotNull(request.getInstalledCapacityMw(), farm::setInstalledCapacityMw);
        ifNotNull(request.getGridDate(), farm::setGridDate);
        ifNotNull(request.getOwner(), farm::setOwner);
        ifNotNull(request.getStatus(), farm::setStatus);
        ifNotNull(request.getNote(), farm::setNote);
        return farmRepository.save(farm);
    }

    @Transactional
    public WindFarm changeStatus(Long id, String status) {
        if (!Dict.isValidFarmStatus(status)) {
            throw new BizException("风场状态只能为 在运 / 检修 / 停运：" + status);
        }
        WindFarm farm = get(id);
        farm.setStatus(status);
        return farmRepository.save(farm);
    }

    @Transactional
    public void remove(Long id) {
        WindFarm farm = get(id);
        long count = turbineRepository.countByFarmId(id);
        if (count > 0) {
            throw new BizException("风场下仍有 " + count + " 个机位，请先删除机位");
        }
        farmRepository.delete(farm);
    }

    /** 机位增删后回写风场的机位数量 */
    @Transactional
    public void refreshTurbineCount(Long farmId) {
        if (farmId == null) {
            return;
        }
        WindFarm farm = farmRepository.findById(farmId).orElse(null);
        if (farm == null) {
            return;
        }
        farm.setTurbineCount((int) turbineRepository.countByFarmId(farmId));
        farmRepository.save(farm);
    }

    public FarmVO toVO(WindFarm farm) {
        FarmVO vo = new FarmVO();
        vo.setId(farm.getId());
        vo.setFarmCode(farm.getFarmCode());
        vo.setFarmName(farm.getFarmName());
        vo.setRegion(farm.getRegion());
        vo.setProvince(farm.getProvince());
        vo.setTimeZone(farm.getTimeZone());
        vo.setTimeZoneName(Dict.timeZoneName(farm.getTimeZone()));
        vo.setOffsetText(Dict.zoneOffsetText(farm.getTimeZone()));
        vo.setInstalledCapacityMw(farm.getInstalledCapacityMw());
        vo.setTurbineCount(farm.getTurbineCount());
        vo.setGridDate(TimeText.date(farm.getGridDate()));
        vo.setOwner(farm.getOwner());
        vo.setStatus(farm.getStatus());
        vo.setStatusName(Dict.farmStatusName(farm.getStatus()));
        vo.setNote(farm.getNote());
        return vo;
    }

    private static <T> void ifNotNull(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}
