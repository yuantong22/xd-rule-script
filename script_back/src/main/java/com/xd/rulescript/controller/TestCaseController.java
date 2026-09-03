package com.xd.rulescript.controller;

import com.xd.rulescript.common.ApiResponse;
import com.xd.rulescript.dto.TestCaseDto;
import com.xd.rulescript.dto.TestCaseIdRequest;
import com.xd.rulescript.dto.TestCaseListRequest;
import com.xd.rulescript.dto.TestCaseSaveRequest;
import com.xd.rulescript.service.TestCaseService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测试用例接口。一律 POST，路径 /api/testcase/<动作>（API 规范）。
 */
@RestController
@RequestMapping("/api/testcase")
public class TestCaseController {

    private final TestCaseService testCaseService;

    public TestCaseController(TestCaseService testCaseService) {
        this.testCaseService = testCaseService;
    }

    @PostMapping("/list")
    public ApiResponse<List<TestCaseDto>> list(@Valid @RequestBody TestCaseListRequest request) {
        return ApiResponse.ok(testCaseService.list(request.ruleId()));
    }

    @PostMapping("/save")
    public ApiResponse<TestCaseDto> save(@Valid @RequestBody TestCaseSaveRequest request) {
        return ApiResponse.ok(testCaseService.save(request));
    }

    @PostMapping("/delete")
    public ApiResponse<Void> delete(@Valid @RequestBody TestCaseIdRequest request) {
        testCaseService.delete(request.testCaseId());
        return ApiResponse.ok();
    }
}
