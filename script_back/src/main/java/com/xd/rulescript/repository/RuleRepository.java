package com.xd.rulescript.repository;

import com.xd.rulescript.entity.Rule;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuleRepository extends JpaRepository<Rule, Long> {

    /** 按名称模糊搜索，排序与分页由 Pageable 决定 */
    Page<Rule> findByNameContaining(String keyword, Pageable pageable);
}
