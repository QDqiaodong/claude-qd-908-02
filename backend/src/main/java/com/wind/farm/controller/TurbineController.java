package com.wind.farm.controller;

import com.wind.farm.common.ApiResponse;
import com.wind.farm.domain.Turbine;
import com.wind.farm.dto.TurbineRequest;
import com.wind.farm.dto.Views.TurbineVO;
import com.wind.farm.service.TurbineService;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 机位（风机）：机位编号同风场内唯一、额定功率 > 0、状态流转受控 */
@RestController
@RequestMapping("/api/turbine")
public class TurbineController {

    private final TurbineService service;

    public TurbineController(TurbineService service) {
        this.service = service;
    }

    @GetMapping("/list")
    public ApiResponse<List<Turbine>> list(@RequestParam(required = false) Long farmId) {
        return ApiResponse.success(service.list(farmId));
    }

    /** 主区大卡片网格：机位号 / 状态灯 / 当前功率 / 今日发电量 */
    @GetMapping("/grid")
    public ApiResponse<List<TurbineVO>> grid(@RequestParam(required = false) Long farmId) {
        return ApiResponse.success(service.grid(farmId));
    }

    @GetMapping("/{id}")
    public ApiResponse<Turbine> get(@PathVariable Long id) {
        return ApiResponse.success(service.get(id));
    }

    @PostMapping
    public ApiResponse<Turbine> create(@RequestBody TurbineRequest request) {
        return ApiResponse.success("机位已创建", service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<Turbine> update(@PathVariable Long id, @RequestBody TurbineRequest request) {
        return ApiResponse.success("机位已更新", service.update(id, request));
    }

    /** 状态流转：并网 ⇄ 检修 / 故障 / 停运 */
    @PostMapping("/{id}/status")
    public ApiResponse<Turbine> changeStatus(@PathVariable Long id, @RequestParam String status) {
        return ApiResponse.success("机位状态已更新", service.changeStatus(id, status));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(@PathVariable Long id) {
        service.remove(id);
        return ApiResponse.success("机位已删除", null);
    }
}
