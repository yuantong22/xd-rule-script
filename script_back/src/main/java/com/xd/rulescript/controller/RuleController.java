package com.xd.rulescript.controller;

import com.xd.rulescript.common.ApiResponse;
import com.xd.rulescript.dto.RuleCreateRequest;
import com.xd.rulescript.dto.RuleDetailResponse;
import com.xd.rulescript.dto.RuleIdRequest;
import com.xd.rulescript.dto.RuleListRequest;
import com.xd.rulescript.dto.RulePageResponse;
import com.xd.rulescript.dto.RuleUpdateRequest;
import com.xd.rulescript.dto.RunRequest;
import com.xd.rulescript.dto.RunResponse;
import com.xd.rulescript.dto.ValidateRequest;
import com.xd.rulescript.dto.ValidateResponse;
import com.xd.rulescript.service.RuleService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 规则接口。全部 POST，路径 /api/rule/<动作>。
 * validate（同步三步校验）与 run（沙箱运行）见下方。
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

    /** 同步校验：语法 + 占位符 + 大模型 CR，全部完成才返回 */
    @PostMapping("/validate")
    public ApiResponse<ValidateResponse> validate(@RequestBody ValidateRequest request) {
        return ApiResponse.ok(ruleService.validate(request));
    }

    /** 沙箱运行，超时 5 秒自动中断 */
    @PostMapping("/run")
    public ApiResponse<RunResponse> run(@RequestBody RunRequest request) {
        return ApiResponse.ok(ruleService.run(request));
    }
}
