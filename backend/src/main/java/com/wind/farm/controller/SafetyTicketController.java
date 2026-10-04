package com.wind.farm.controller;

import com.wind.farm.common.ApiResponse;
import com.wind.farm.domain.IsolationPoint;
import com.wind.farm.domain.IsolationTemplate;
import com.wind.farm.domain.SafetyTicket;
import com.wind.farm.dto.IsolationConfirmRequest;
import com.wind.farm.dto.IsolationPointRequest;
import com.wind.farm.dto.IsolationTemplateRequest;
import com.wind.farm.dto.TicketCreateRequest;
import com.wind.farm.dto.Views.IsolationPointVO;
import com.wind.farm.dto.Views.SafetyTicketVO;
import com.wind.farm.dto.Views.TemplateVO;
import com.wind.farm.dto.Views.TicketBriefVO;
import com.wind.farm.service.SafetyTicketService;
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

/**
 * 安全隔离票闭环：
 * 模板按检修类型维护；票从草稿 → 确认中 → 已隔离 → 复电中 → 已恢复单向推进；
 * 隔离点升序双人确认、复电降序逐项解除；模板新增风险点须补入或留痕后才能隔离。
 */
@RestController
@RequestMapping("/api/isolation")
public class SafetyTicketController {

    private final SafetyTicketService service;

    public SafetyTicketController(SafetyTicketService service) {
        this.service = service;
    }

    // ---------------- 隔离点模板 ----------------

    @GetMapping("/template/list")
    public ApiResponse<List<TemplateVO>> templates(@RequestParam(required = false) String maintType) {
        List<TemplateVO> result = new ArrayList<>();
        for (IsolationTemplate tpl : service.listTemplates(maintType)) {
            result.add(service.toTemplateVO(tpl));
        }
        return ApiResponse.success(result);
    }

    @PostMapping("/template")
    public ApiResponse<TemplateVO> createTemplate(@RequestBody IsolationTemplateRequest request) {
        return ApiResponse.success("模板隔离点已创建", service.toTemplateVO(service.createTemplate(request)));
    }

    @PutMapping("/template/{id}")
    public ApiResponse<TemplateVO> updateTemplate(@PathVariable Long id,
            @RequestBody IsolationTemplateRequest request) {
        return ApiResponse.success("模板隔离点已更新（不影响已创建的票，新版本会差异提示）",
                service.toTemplateVO(service.updateTemplate(id, request)));
    }

    @DeleteMapping("/template/{id}")
    public ApiResponse<Void> removeTemplate(@PathVariable Long id) {
        service.removeTemplate(id);
        return ApiResponse.success("模板隔离点已删除", null);
    }

    // ---------------- 隔离票 ----------------

    @GetMapping("/ticket/list")
    public ApiResponse<List<TicketBriefVO>> list(@RequestParam(required = false) Long turbineId,
            @RequestParam(required = false) Long farmId) {
        List<TicketBriefVO> result = new ArrayList<>();
        for (SafetyTicket ticket : service.list(turbineId, farmId)) {
            result.add(service.briefOf(ticket));
        }
        return ApiResponse.success(result);
    }

    @GetMapping("/ticket/{id}")
    public ApiResponse<SafetyTicketVO> get(@PathVariable Long id) {
        return ApiResponse.success(service.getVO(id));
    }

    /** 按工单查票（工单页直接用） */
    @GetMapping("/ticket/by-order/{workOrderId}")
    public ApiResponse<SafetyTicketVO> byOrder(@PathVariable Long workOrderId) {
        SafetyTicket ticket = service.getByOrder(workOrderId);
        return ticket == null ? ApiResponse.success(null) : ApiResponse.success(service.toVO(ticket));
    }

    @PostMapping("/ticket")
    public ApiResponse<SafetyTicketVO> create(@RequestBody TicketCreateRequest request) {
        SafetyTicket ticket = service.createTicket(request);
        return ApiResponse.success("隔离票已创建（隔离点已按模板快照带入）", service.getVO(ticket.getId()));
    }

    @DeleteMapping("/ticket/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.deleteTicket(id);
        return ApiResponse.success("草稿隔离票已删除", null);
    }

    /** 草稿 → 确认中（机位自动转检修） */
    @PostMapping("/ticket/{id}/start-confirm")
    public ApiResponse<SafetyTicketVO> startConfirm(@PathVariable Long id) {
        return ApiResponse.success("已开始隔离确认", service.getVO(service.startConfirm(id).getId()));
    }

    /** 确认中 → 已隔离：全部隔离点双人确认、模板新增风险已处理后生效 */
    @PostMapping("/ticket/{id}/complete-isolation")
    public ApiResponse<SafetyTicketVO> completeIsolation(@PathVariable Long id) {
        return ApiResponse.success("全部隔离确认完成，隔离已生效", service.getVO(service.completeIsolation(id).getId()));
    }

    /** 已隔离 → 复电中：必须工单已验收合格 */
    @PostMapping("/ticket/{id}/start-restore")
    public ApiResponse<SafetyTicketVO> startRestore(@PathVariable Long id) {
        return ApiResponse.success("已开始按逆序复电", service.getVO(service.startRestore(id).getId()));
    }

    /** 复电中 → 已恢复：所有点严格逆序解除后完成，机位才可人工并网 */
    @PostMapping("/ticket/{id}/complete-restore")
    public ApiResponse<SafetyTicketVO> completeRestore(@PathVariable Long id) {
        return ApiResponse.success("隔离已全部恢复，机位可经核对后恢复并网",
                service.getVO(service.completeRestore(id).getId()));
    }

    /** 模板新增风险点处理：IMPORT 补入本票 / IGNORE 责任人留痕忽略 */
    @PostMapping("/ticket/{id}/template-risk")
    public ApiResponse<SafetyTicketVO> acknowledgeRisk(@PathVariable Long id,
            @RequestParam String action, @RequestParam(required = false) String operator) {
        SafetyTicket ticket = service.acknowledgeTemplateRisk(id, action, operator);
        return ApiResponse.success("IMPORT".equals(action) ? "模板新增风险点已补入本票" : "已对差异留痕忽略",
                service.getVO(ticket.getId()));
    }

    // ---------------- 票内隔离点 ----------------

    @PostMapping("/point")
    public ApiResponse<IsolationPointVO> addPoint(@RequestBody IsolationPointRequest request) {
        IsolationPoint point = service.addPoint(request);
        return ApiResponse.success("隔离点已补充到本票", service.getVO(point.getTicketId())
                .getPoints().stream().filter(p -> p.getId().equals(point.getId())).findFirst().orElse(null));
    }

    @PutMapping("/point/{id}")
    public ApiResponse<IsolationPoint> updatePoint(@PathVariable Long id,
            @RequestBody IsolationPointRequest request) {
        return ApiResponse.success("隔离点已更新", service.updatePoint(id, request));
    }

    @DeleteMapping("/point/{id}")
    public ApiResponse<Void> removePoint(@PathVariable Long id) {
        service.removePoint(id);
        return ApiResponse.success("隔离点已删除", null);
    }

    /** 单个隔离点双人隔离确认（独立事务，幂等，部分失败不影响已完成项） */
    @PostMapping("/point/{id}/isolate")
    public ApiResponse<SafetyTicketVO> isolate(@PathVariable Long id, @RequestBody IsolationConfirmRequest request) {
        IsolationPoint point = service.confirmPoint(id, request);
        return ApiResponse.success("隔离点「" + point.getPointName() + "」已双人确认",
                service.getVO(point.getTicketId()));
    }

    /** 复查标记隔离失效（票会退回确认中，需重新确认） */
    @PostMapping("/point/{id}/invalid")
    public ApiResponse<SafetyTicketVO> invalid(@PathVariable Long id,
            @RequestParam(required = false) String operator, @RequestParam(required = false) String reason) {
        IsolationPoint point = service.markInvalid(id, operator, reason);
        return ApiResponse.success("已标记隔离失效，现场作业暂停，须重新确认", service.getVO(point.getTicketId()));
    }

    /** 单个隔离点逆序复电解除（独立事务、幂等，跳项会被阻止并给出下一应处理项） */
    @PostMapping("/point/{id}/restore")
    public ApiResponse<SafetyTicketVO> restore(@PathVariable Long id, @RequestBody IsolationConfirmRequest request) {
        IsolationPoint point = service.restorePoint(id, request);
        return ApiResponse.success("隔离点「" + point.getPointName() + "」已按逆序解除",
                service.getVO(point.getTicketId()));
    }
}
