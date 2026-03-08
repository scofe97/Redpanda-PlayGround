package com.study.playground.ticket.mapper;

import com.study.playground.ticket.domain.Ticket;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TicketMapper {
    List<Ticket> findAll();
    Ticket findById(@Param("id") Long id);
    void insert(Ticket ticket);
    void update(Ticket ticket);
    void updateStatus(@Param("id") Long id, @Param("status") String status);
    void deleteById(@Param("id") Long id);
}
