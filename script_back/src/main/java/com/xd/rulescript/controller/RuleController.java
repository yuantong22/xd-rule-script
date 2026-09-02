package com.xd.rulescript.controller;

import com.xd.rulescript.common.ApiResponse;
import com.xd.rulescript.dto.RuleCreateRequest;
import com.xd.rulescript.dto.RuleDetailResponse;
import com.xd.rulescript.dto.RuleIdRequest;
import com.xd.rulescript.dto.RuleListRequest;
import com.xd.rulescript.dto.RulePageResponse;
import com.xd.rulescript.dto.RuleUpdateRequest;
import com.xd.rulescript.service.RuleService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 规则接口。全部 POST，路径 /api/rule/<动作>。
 * validate 与 run 在任务 12 补上。
 */
@RestController
@RequestMapping("/api/rule")
public class RuleController {

    private final RuleService ruleService;

    public RuleController(RuleService ruleService) {
        this.ruleService = ruleService;
    }

    @PostMapping("/list")
    public ApiResponse<RulePageResponse> list(@RequestBody RuleListRequest request) {
        return ApiResponse.ok(ruleService.list(request));
    }

    @PostMapping("/create")
    public ApiResponse<RuleDetailResponse> create(@Valid @RequestBody RuleCreateRequest request) {
        return ApiResponse.ok(ruleService.create(request));
    }

    @PostMapping("/detail")
    public ApiResponse<RuleDetailResponse> detail(@Valid @RequestBody RuleIdRequest request) {
        return ApiResponse.ok(ruleService.detail(request.ruleId()));
    }

    @PostMapping("/update")
    public ApiResponse<RuleDetailResponse> update(@Valid @RequestBody RuleUpdateRequest request) {
        return ApiResponse.ok(ruleService.update(request));
    }

    @PostMapping("/delete")
    public ApiResponse<Void> delete(@Valid @RequestBody RuleIdRequest request) {
        ruleService.delete(request.ruleId());
        return ApiResponse.ok();
    }
}
