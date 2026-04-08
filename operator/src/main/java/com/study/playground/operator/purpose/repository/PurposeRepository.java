package com.study.playground.operator.purpose.repository;

import com.study.playground.operator.purpose.domain.Purpose;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PurposeRepository extends JpaRepository<Purpose, Long> {

    @EntityGraph(attributePaths = {"entries"})
    List<Purpose> findAllByOrderByName();

    @EntityGraph(attributePaths = {"entries"})
    Optional<Purpose> findWithEntriesById(Long id);
}
