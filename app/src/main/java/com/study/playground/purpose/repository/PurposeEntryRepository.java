package com.study.playground.purpose.repository;

import com.study.playground.purpose.domain.PurposeEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PurposeEntryRepository extends JpaRepository<PurposeEntry, Long> {

    List<PurposeEntry> findByPurposeId(Long purposeId);

    void deleteByPurposeId(Long purposeId);

    @Query("SELECT COUNT(e) FROM PurposeEntry e WHERE e.toolId = :toolId")
    int countByToolId(Long toolId);
}
