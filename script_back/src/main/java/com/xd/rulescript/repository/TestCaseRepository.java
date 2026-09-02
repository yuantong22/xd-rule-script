package com.xd.rulescript.repository;

import com.xd.rulescript.entity.TestCase;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestCaseRepository extends JpaRepository<TestCase, Long> {

    List<TestCase> findByRuleIdOrderByIdAsc(Long ruleId);

    void deleteByRuleId(Long ruleId);
}
