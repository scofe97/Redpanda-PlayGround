package com.study.playground.supporttool.repository;

import com.study.playground.supporttool.domain.SupportTool;
import com.study.playground.supporttool.domain.ToolCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SupportToolRepository extends JpaRepository<SupportTool, Long> {

    List<SupportTool> findAllByOrderByCategoryAscNameAsc();

    Optional<SupportTool> findFirstByCategoryAndActiveTrue(ToolCategory category);

    List<SupportTool> findByCategoryOrderByName(ToolCategory category);
}
