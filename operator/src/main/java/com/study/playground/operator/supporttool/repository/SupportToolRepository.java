package com.study.playground.operator.supporttool.repository;

import com.study.playground.operator.supporttool.domain.SupportTool;
import com.study.playground.operator.supporttool.domain.ToolCategory;
import com.study.playground.operator.supporttool.domain.ToolImplementation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SupportToolRepository extends JpaRepository<SupportTool, Long> {

    List<SupportTool> findAllByOrderByCategoryAscNameAsc();

    Optional<SupportTool> findFirstByCategoryAndActiveTrue(ToolCategory category);

    List<SupportTool> findByCategoryOrderByName(ToolCategory category);

    List<SupportTool> findByImplementationAndActiveTrue(ToolImplementation implementation);
}
