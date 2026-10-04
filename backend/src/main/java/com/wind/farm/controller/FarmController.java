package com.wind.farm.controller;

import com.wind.farm.common.ApiResponse;
import com.wind.farm.domain.WindFarm;
import com.wind.farm.dto.FarmRequest;
import com.wind.farm.dto.Views.FarmVO;
import com.wind.farm.service.FarmService;
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

/** 风场：风场编号唯一、必须带时区标识 */
@RestController
@RequestMapping("/api/farm")
public class FarmController {

    private final FarmService service;

    public FarmController(FarmService service) {
        this.service = service;
    }

    @GetMapping("/list")
    public ApiResponse<List<WindFarm>> list() {
        return ApiResponse.success(service.list());
    }

    /** 带时区名称 / 偏移的视图对象，供顶部风场切换器使用 */
    @GetMapping("/vo")
    public ApiResponse<List<FarmVO>> listVO() {
        return ApiResponse.success(service.listVO());
    }

    @GetMapping("/{id}")
    public ApiResponse<WindFarm> get(@PathVariable Long id) {
        return ApiResponse.success(service.get(id));
    }

    @PostMapping
    public ApiResponse<WindFarm> create(@RequestBody FarmRequest request) {
        return ApiResponse.success("风场已创建", service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<WindFarm> update(@PathVariable Long id, @RequestBody FarmRequest request) {
        return ApiResponse.success("风场已更新", service.update(id, request));
    }

    @PostMapping("/{id}/status")
    public ApiResponse<WindFarm> changeStatus(@PathVariable Long id, @RequestParam String status) {
        return ApiResponse.success("风场状态已更新", service.changeStatus(id, status));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(@PathVariable Long id) {
        service.remove(id);
        return ApiResponse.success("风场已删除", null);
    }
}
