package com.wind.farm.controller;

import com.wind.farm.common.ApiResponse;
import com.wind.farm.domain.GridLimitOrder;
import com.wind.farm.dto.GridAllocationRequest;
import com.wind.farm.dto.GridDeviationHandleRequest;
import com.wind.farm.dto.GridLimitOrderRequest;
import com.wind.farm.dto.GridStatusRequest;
import com.wind.farm.dto.Views.GridLimitDeviationVO;
import com.wind.farm.dto.Views.GridLimitOrderVO;
import com.wind.farm.dto.Views.GridLimitPreviewVO;
import com.wind.farm.service.GridLimitService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 电网限功率指令：登记、分摊、执行、重叠、偏差处置和完整轨迹均在后台落库。 */
@RestController
@RequestMapping("/api/grid-limit")
public class GridLimitController {

    private final GridLimitService service;

    public GridLimitController(GridLimitService service) {
        this.service = service;
    }

    @GetMapping("/order/vo")
    public ApiResponse<List<GridLimitOrderVO>> list(@RequestParam(required = false) Long farmId) {
        return ApiResponse.success(service.listVO(farmId));
    }

    @GetMapping("/order/{id}")
    public ApiResponse<GridLimitOrderVO> get(@PathVariable Long id) {
        return ApiResponse.success(service.getVO(id));
    }

    @PostMapping("/order")
    public ApiResponse<GridLimitOrder> create(@RequestBody GridLimitOrderRequest request) {
        return ApiResponse.success("限功率指令已登记", service.create(request));
    }

    @PutMapping("/order/{id}")
    public ApiResponse<GridLimitOrder> update(@PathVariable Long id, @RequestBody GridLimitOrderRequest request) {
        return ApiResponse.success("限功率指令已修改", service.update(id, request));
    }

    @PostMapping("/order/{id}/preview")
    public ApiResponse<GridLimitPreviewVO> preview(@PathVariable Long id,
            @RequestBody(required = false) GridAllocationRequest request) {
        return ApiResponse.success(service.preview(id, request));
    }

    @PostMapping("/order/{id}/allocation")
    public ApiResponse<Void> allocate(@PathVariable Long id, @RequestBody(required = false) GridAllocationRequest request) {
        service.confirmAllocations(id, request);
        return ApiResponse.success("机位分摊已确认", null);
    }

    @PostMapping("/order/{id}/start")
    public ApiResponse<GridLimitOrder> start(@PathVariable Long id, @RequestBody(required = false) GridStatusRequest request) {
        return ApiResponse.success("指令已进入执行", service.start(id, request));
    }

    @PostMapping("/order/{id}/pause")
    public ApiResponse<GridLimitOrder> pause(@PathVariable Long id, @RequestBody(required = false) GridStatusRequest request) {
        return ApiResponse.success("指令已暂停", service.pause(id, request));
    }

    @PostMapping("/order/{id}/resume")
    public ApiResponse<GridLimitOrder> resume(@PathVariable Long id, @RequestBody(required = false) GridStatusRequest request) {
        return ApiResponse.success("指令已恢复执行", service.resume(id, request));
    }

    @PostMapping("/order/{id}/end")
    public ApiResponse<GridLimitOrder> end(@PathVariable Long id, @RequestBody GridStatusRequest request) {
        return ApiResponse.success("指令已结束并留存解除结果", service.end(id, request));
    }

    @PostMapping("/order/{id}/cancel")
    public ApiResponse<GridLimitOrder> cancel(@PathVariable Long id, @RequestBody(required = false) GridStatusRequest request) {
        return ApiResponse.success("指令已取消", service.cancel(id, request));
    }

    @PostMapping("/order/{id}/reallocate")
    public ApiResponse<Void> reallocate(@PathVariable Long id, @RequestBody(required = false) GridStatusRequest request) {
        service.refreshRuntimeAllocation(id, request);
        return ApiResponse.success("分摊已按当前可运行状态刷新", null);
    }

    @GetMapping("/deviation/vo")
    public ApiResponse<List<GridLimitDeviationVO>> deviations(@RequestParam(required = false) Long farmId,
            @RequestParam(required = false) Long orderId,
            @RequestParam(required = false) Boolean openOnly) {
        return ApiResponse.success(service.deviations(farmId, orderId, openOnly));
    }

    @PostMapping("/deviation/{id}/handle")
    public ApiResponse<Void> handle(@PathVariable Long id, @RequestBody GridDeviationHandleRequest request) {
        service.handleDeviation(id, request);
        return ApiResponse.success("偏差处置已记录", null);
    }

    @PostMapping("/deviation/{id}/review")
    public ApiResponse<Void> review(@PathVariable Long id, @RequestBody GridDeviationHandleRequest request) {
        service.reviewDeviation(id, request);
        return ApiResponse.success("偏差复核已记录", null);
    }

    @PostMapping("/deviation/{id}/release")
    public ApiResponse<Void> release(@PathVariable Long id, @RequestBody GridDeviationHandleRequest request) {
        service.releaseDeviation(id, request);
        return ApiResponse.success("偏差解除已记录", null);
    }
}
