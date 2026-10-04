package com.wind.farm.controller;

import com.wind.farm.common.ApiResponse;
import com.wind.farm.domain.PartReplacement;
import com.wind.farm.domain.SparePart;
import com.wind.farm.dto.ReplacementRequest;
import com.wind.farm.dto.ReplacementResult;
import com.wind.farm.dto.SparePartRequest;
import com.wind.farm.dto.Views.ReplacementVO;
import com.wind.farm.dto.Views.SparePartVO;
import com.wind.farm.service.SparePartService;
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

/** 备件与更换：编号唯一、安全库存非负、更换数量不超库存、低库存出库打预警标记 */
@RestController
@RequestMapping("/api/part")
public class SparePartController {

    private final SparePartService service;

    public SparePartController(SparePartService service) {
        this.service = service;
    }

    @GetMapping("/list")
    public ApiResponse<List<SparePart>> list(@RequestParam(required = false) String category) {
        return ApiResponse.success(service.list(category));
    }

    /** 带库存条与低库存标记的视图对象 */
    @GetMapping("/vo")
    public ApiResponse<List<SparePartVO>> listVO(@RequestParam(required = false) String category) {
        return ApiResponse.success(service.listVO(category));
    }

    @GetMapping("/{id}")
    public ApiResponse<SparePart> get(@PathVariable Long id) {
        return ApiResponse.success(service.get(id));
    }

    @PostMapping
    public ApiResponse<SparePart> create(@RequestBody SparePartRequest request) {
        return ApiResponse.success("备件已创建", service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<SparePart> update(@PathVariable Long id, @RequestBody SparePartRequest request) {
        return ApiResponse.success("备件已更新", service.update(id, request));
    }

    /** 步进器改库存：delta 为正入库、为负出库 */
    @PostMapping("/{id}/stock")
    public ApiResponse<SparePart> changeStock(@PathVariable Long id, @RequestParam Integer delta) {
        return ApiResponse.success("库存已调整", service.changeStock(id, delta));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(@PathVariable Long id) {
        service.remove(id);
        return ApiResponse.success("备件已删除", null);
    }

    // ---------------- 更换登记 ----------------

    @GetMapping("/replacement")
    public ApiResponse<List<PartReplacement>> replacements(@RequestParam(required = false) Long partId,
            @RequestParam(required = false) Long turbineId) {
        return ApiResponse.success(service.replacements(partId, turbineId));
    }

    @GetMapping("/replacement/vo")
    public ApiResponse<List<ReplacementVO>> replacementVOs(@RequestParam(required = false) Long partId,
            @RequestParam(required = false) Long turbineId) {
        return ApiResponse.success(service.replacementVOs(partId, turbineId));
    }

    @PostMapping("/replacement")
    public ApiResponse<ReplacementResult> replace(@RequestBody ReplacementRequest request) {
        ReplacementResult result = service.replace(request);
        String message;
        if (result.isDuplicated()) {
            message = "该更换此前已登记成功，本次未重复扣减、未重复生成履历";
        } else if (result.isTurbineFilledFromOrder()) {
            message = "更换登记已完成；只选了工单，机位已按工单 " + nullToDash(result.getOrderNo())
                    + " 自动补齐为 " + nullToDash(result.getUnitCode()) + "，请核对";
        } else {
            message = "更换登记已完成";
        }
        return ApiResponse.success(message, result);
    }

    private static String nullToDash(String value) {
        return value == null ? "—" : value;
    }
}
