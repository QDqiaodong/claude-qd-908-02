package com.wind.farm.controller;

import com.wind.farm.common.ApiResponse;
import com.wind.farm.domain.RunLog;
import com.wind.farm.dto.RunLogRequest;
import com.wind.farm.dto.Views.RunLogVO;
import com.wind.farm.service.RunLogService;
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

/** 运行日志：时刻重复登记拦截、风速非负、功率不超额定、日发电量不超 额定功率 × 24h */
@RestController
@RequestMapping("/api/runlog")
public class RunLogController {

    private final RunLogService service;

    public RunLogController(RunLogService service) {
        this.service = service;
    }

    @GetMapping("/list")
    public ApiResponse<List<RunLog>> list(@RequestParam(required = false) Long turbineId,
            @RequestParam(required = false) Long farmId) {
        return ApiResponse.success(service.list(turbineId, farmId));
    }

    /** 带时区还原文本的视图对象（recordLocal / recordTz） */
    @GetMapping("/vo")
    public ApiResponse<List<RunLogVO>> listVO(@RequestParam(required = false) Long turbineId,
            @RequestParam(required = false) Long farmId) {
        return ApiResponse.success(service.listVO(turbineId, farmId));
    }

    @GetMapping("/{id}")
    public ApiResponse<RunLog> get(@PathVariable Long id) {
        return ApiResponse.success(service.get(id));
    }

    @PostMapping
    public ApiResponse<RunLog> create(@RequestBody RunLogRequest request) {
        return ApiResponse.success("运行日志已登记", service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<RunLog> update(@PathVariable Long id, @RequestBody RunLogRequest request) {
        return ApiResponse.success("运行日志已更新", service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(@PathVariable Long id) {
        service.remove(id);
        return ApiResponse.success("运行日志已删除", null);
    }
}
