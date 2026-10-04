package com.wind.farm.controller;

import com.wind.farm.common.ApiResponse;
import com.wind.farm.domain.WorkItem;
import com.wind.farm.domain.WorkOrder;
import com.wind.farm.dto.AcceptanceRequest;
import com.wind.farm.dto.WorkItemRequest;
import com.wind.farm.dto.WorkOrderRequest;
import com.wind.farm.dto.Views.WorkItemVO;
import com.wind.farm.dto.Views.WorkOrderVO;
import com.wind.farm.service.WorkOrderService;
import java.util.ArrayList;
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

/** 检修工单与作业项：工单号唯一、检修类型 5 选 1、阶段流转不跳步、提验收前作业项必须有结果 */
@RestController
@RequestMapping("/api/workorder")
public class WorkOrderController {

    private final WorkOrderService service;

    public WorkOrderController(WorkOrderService service) {
        this.service = service;
    }

    @GetMapping("/list")
    public ApiResponse<List<WorkOrder>> list(@RequestParam(required = false) Long turbineId,
            @RequestParam(required = false) Long farmId) {
        return ApiResponse.success(service.list(turbineId, farmId));
    }

    /** 带作业项清单与进度的视图对象，kanban 四列直接用它 */
    @GetMapping("/vo")
    public ApiResponse<List<WorkOrderVO>> listVO(@RequestParam(required = false) Long turbineId,
            @RequestParam(required = false) Long farmId) {
        return ApiResponse.success(service.listVO(turbineId, farmId));
    }

    @GetMapping("/{id}")
    public ApiResponse<WorkOrderVO> get(@PathVariable Long id) {
        return ApiResponse.success(service.toVO(service.get(id)));
    }

    @GetMapping("/{id}/items")
    public ApiResponse<List<WorkItemVO>> items(@PathVariable Long id) {
        List<WorkItemVO> result = new ArrayList<>();
        for (WorkItem item : service.items(id)) {
            result.add(service.toItemVO(item));
        }
        return ApiResponse.success(result);
    }

    @GetMapping("/kanban")
    public ApiResponse<List<WorkOrderVO>> kanban(@RequestParam(required = false) Long farmId) {
        return ApiResponse.success(service.listVO(null, farmId));
    }

    @PostMapping
    public ApiResponse<WorkOrder> create(@RequestBody WorkOrderRequest request) {
        return ApiResponse.success("检修工单已创建", service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<WorkOrder> update(@PathVariable Long id, @RequestBody WorkOrderRequest request) {
        return ApiResponse.success("检修工单已更新", service.update(id, request));
    }

    /** 阶段推进：待派 → 作业中 → 待验收 → 已闭环 */
    @PostMapping("/{id}/status")
    public ApiResponse<WorkOrderVO> changeStatus(@PathVariable Long id, @RequestParam String status) {
        return ApiResponse.success("工单阶段已推进", service.toVO(service.changeStatus(id, status)));
    }

    /** 验收登记：合格后隔离票才能开始复电；驳回退回作业中 */
    @PostMapping("/{id}/accept")
    public ApiResponse<WorkOrderVO> accept(@PathVariable Long id, @RequestBody AcceptanceRequest request) {
        return ApiResponse.success("PASS".equals(request.getResult()) ? "验收合格，可开始复电" : "已驳回，工单退回作业中",
                service.toVO(service.accept(id, request)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(@PathVariable Long id) {
        service.remove(id);
        return ApiResponse.success("检修工单已删除", null);
    }

    // ---------------- 作业项 ----------------

    @PostMapping("/item")
    public ApiResponse<WorkItem> createItem(@RequestBody WorkItemRequest request) {
        return ApiResponse.success("作业项已添加", service.createItem(request));
    }

    @PutMapping("/item/{id}")
    public ApiResponse<WorkItem> updateItem(@PathVariable Long id, @RequestBody WorkItemRequest request) {
        return ApiResponse.success("作业项已更新", service.updateItem(id, request));
    }

    @DeleteMapping("/item/{id}")
    public ApiResponse<Void> removeItem(@PathVariable Long id) {
        service.removeItem(id);
        return ApiResponse.success("作业项已删除", null);
    }
}
