package com.wind.farm.controller;

import com.wind.farm.common.ApiResponse;
import com.wind.farm.domain.GridLimitCase;
import com.wind.farm.domain.GridLimitEvent;
import com.wind.farm.domain.GridPowerAllocation;
import com.wind.farm.domain.GridPowerCommand;
import com.wind.farm.dto.GridAllocationRequest;
import com.wind.farm.dto.GridCaseHandleRequest;
import com.wind.farm.dto.GridCaseReleaseRequest;
import com.wind.farm.dto.GridCaseReviewRequest;
import com.wind.farm.dto.GridCommandActionRequest;
import com.wind.farm.dto.GridCommandRequest;
import com.wind.farm.dto.GridViews.CaseVO;
import com.wind.farm.dto.GridViews.CommandVO;
import com.wind.farm.dto.GridViews.EventVO;
import com.wind.farm.service.GridCommandService;
import com.wind.farm.service.GridLimitService;
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

/** 电网限功率指令、机位分摊、重叠关系、偏差事件与处置轨迹。 */
@RestController
@RequestMapping("/api/grid")
public class GridPowerController {

    private final GridCommandService commandService;
    private final GridLimitService limitService;

    public GridPowerController(GridCommandService commandService, GridLimitService limitService) {
        this.commandService = commandService;
        this.limitService = limitService;
    }

    @GetMapping("/command/list")
    public ApiResponse<List<GridPowerCommand>> list(@RequestParam(required = false) Long farmId) {
        return ApiResponse.success(commandService.list(farmId));
    }

    @GetMapping("/command/vo")
    public ApiResponse<List<CommandVO>> listVO(@RequestParam(required = false) Long farmId) {
        return ApiResponse.success(commandService.list(farmId).stream().map(commandService::toVO).toList());
    }

    @GetMapping("/command/{id}")
    public ApiResponse<GridPowerCommand> get(@PathVariable Long id) {
        return ApiResponse.success(commandService.get(id));
    }

    @GetMapping("/command/{id}/vo")
    public ApiResponse<CommandVO> getVO(@PathVariable Long id) {
        return ApiResponse.success(commandService.toVO(commandService.get(id)));
    }

    @PostMapping("/command")
    public ApiResponse<CommandVO> create(@RequestBody GridCommandRequest request) {
        GridPowerCommand command = commandService.create(request);
        return ApiResponse.success("限功率指令已登记，已形成启动前分摊预览", commandService.toVO(command));
    }

    @PutMapping("/command/{id}")
    public ApiResponse<CommandVO> update(@PathVariable Long id, @RequestBody GridCommandRequest request) {
        GridPowerCommand command = commandService.update(id, request);
        return ApiResponse.success("限功率指令已更新", commandService.toVO(command));
    }

    @DeleteMapping("/command/{id}")
    public ApiResponse<Void> remove(@PathVariable Long id) {
        commandService.remove(id);
        return ApiResponse.success("待执行指令已删除", null);
    }

    @PostMapping("/command/{id}/allocation/preview")
    public ApiResponse<List<GridPowerAllocation>> preview(@PathVariable Long id) {
        return ApiResponse.success("已按当前可运行机位重新生成分摊预览", commandService.previewAllocation(id));
    }

    @PostMapping("/command/{id}/allocation/confirm")
    public ApiResponse<List<GridPowerAllocation>> confirm(@PathVariable Long id,
            @RequestBody(required = false) GridAllocationRequest request) {
        return ApiResponse.success("机位分摊已确认", commandService.confirmAllocation(id, request));
    }

    @PostMapping("/command/{id}/start")
    public ApiResponse<CommandVO> start(@PathVariable Long id, @RequestBody(required = false) GridCommandActionRequest request) {
        return ApiResponse.success("指令已进入执行中", commandService.toVO(commandService.start(id, request)));
    }

    @PostMapping("/command/{id}/pause")
    public ApiResponse<CommandVO> pause(@PathVariable Long id, @RequestBody(required = false) GridCommandActionRequest request) {
        return ApiResponse.success("指令已暂停", commandService.toVO(commandService.pause(id, request)));
    }

    @PostMapping("/command/{id}/resume")
    public ApiResponse<CommandVO> resume(@PathVariable Long id, @RequestBody(required = false) GridCommandActionRequest request) {
        return ApiResponse.success("指令已恢复执行", commandService.toVO(commandService.resume(id, request)));
    }

    @PostMapping("/command/{id}/finish")
    public ApiResponse<CommandVO> finish(@PathVariable Long id, @RequestBody(required = false) GridCommandActionRequest request) {
        return ApiResponse.success("指令已结束", commandService.toVO(commandService.finish(id, request)));
    }

    @PostMapping("/command/{id}/cancel")
    public ApiResponse<CommandVO> cancel(@PathVariable Long id, @RequestBody(required = false) GridCommandActionRequest request) {
        return ApiResponse.success("指令已取消", commandService.toVO(commandService.cancel(id, request)));
    }

    @PostMapping("/farm/{farmId}/sync")
    public ApiResponse<Integer> sync(@PathVariable Long farmId) {
        return ApiResponse.success("已按当前绝对时刻同步到期指令", commandService.syncFarm(farmId));
    }

    @GetMapping("/event/list")
    public ApiResponse<List<GridLimitEvent>> events(@RequestParam(required = false) Long farmId) {
        return ApiResponse.success(limitService.listEvents(farmId));
    }

    @GetMapping("/event/vo")
    public ApiResponse<List<EventVO>> eventsVO(@RequestParam(required = false) Long farmId) {
        return ApiResponse.success(limitService.listEvents(farmId).stream().map(limitService::toEventVO).toList());
    }

    @GetMapping("/case/list")
    public ApiResponse<List<GridLimitCase>> cases(@RequestParam(required = false) Long farmId) {
        return ApiResponse.success(limitService.listCases(farmId));
    }

    @GetMapping("/case/vo")
    public ApiResponse<List<CaseVO>> casesVO(@RequestParam(required = false) Long farmId) {
        return ApiResponse.success(limitService.listCases(farmId).stream().map(limitService::toCaseVO).toList());
    }

    @GetMapping("/case/{id}/vo")
    public ApiResponse<CaseVO> caseVO(@PathVariable Long id) {
        return ApiResponse.success(limitService.toCaseVO(limitService.getCase(id)));
    }

    @PostMapping("/case/{id}/handle")
    public ApiResponse<CaseVO> handle(@PathVariable Long id, @RequestBody GridCaseHandleRequest request) {
        return ApiResponse.success("处置结果已记录", limitService.toCaseVO(limitService.handleCase(id, request)));
    }

    @PostMapping("/case/{id}/review")
    public ApiResponse<CaseVO> review(@PathVariable Long id, @RequestBody GridCaseReviewRequest request) {
        return ApiResponse.success("复核结果已记录", limitService.toCaseVO(limitService.reviewCase(id, request)));
    }

    @PostMapping("/case/{id}/release")
    public ApiResponse<CaseVO> release(@PathVariable Long id, @RequestBody GridCaseReleaseRequest request) {
        return ApiResponse.success("解除结果已记录", limitService.toCaseVO(limitService.releaseCase(id, request)));
    }
}
