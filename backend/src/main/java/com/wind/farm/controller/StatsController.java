package com.wind.farm.controller;

import com.wind.farm.common.ApiResponse;
import com.wind.farm.dto.FarmEnergyDTO;
import com.wind.farm.dto.PartCategoryDTO;
import com.wind.farm.dto.PeriodEnergyDTO;
import com.wind.farm.dto.TurbineEnergyDTO;
import com.wind.farm.dto.WorkOrderStatusDTO;
import com.wind.farm.service.StatsService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 发电统计：日 / 月发电量、等效利用小时，按风场 / 机位两种口径聚合 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService service;

    public StatsController(StatsService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        return ApiResponse.success(service.overview());
    }

    /** 日发电量趋势 */
    @GetMapping("/daily")
    public ApiResponse<List<PeriodEnergyDTO>> daily(@RequestParam(required = false) Long farmId) {
        return ApiResponse.success(service.daily(farmId));
    }

    /** 月发电量 */
    @GetMapping("/monthly")
    public ApiResponse<List<PeriodEnergyDTO>> monthly(@RequestParam(required = false) Long farmId) {
        return ApiResponse.success(service.monthly(farmId));
    }

    /** 按风场口径聚合 */
    @GetMapping("/by-farm")
    public ApiResponse<List<FarmEnergyDTO>> byFarm() {
        return ApiResponse.success(service.byFarm());
    }

    /** 按机位口径聚合 */
    @GetMapping("/by-turbine")
    public ApiResponse<List<TurbineEnergyDTO>> byTurbine(@RequestParam(required = false) Long farmId) {
        return ApiResponse.success(service.byTurbine(farmId));
    }

    /** 检修工单状态分布 */
    @GetMapping("/work-order-status")
    public ApiResponse<List<WorkOrderStatusDTO>> workOrderStatus() {
        return ApiResponse.success(service.workOrderStatus());
    }

    /** 检修类型分布 */
    @GetMapping("/maint-type")
    public ApiResponse<List<WorkOrderStatusDTO>> maintType() {
        return ApiResponse.success(service.maintTypeStat());
    }

    /** 备件分类库存分布 */
    @GetMapping("/part-category")
    public ApiResponse<List<PartCategoryDTO>> partCategory() {
        return ApiResponse.success(service.partCategory());
    }

    /** 词典：前端下拉框数据源 */
    @GetMapping("/dict")
    public ApiResponse<Map<String, Object>> dict() {
        return ApiResponse.success(service.dict());
    }
}
