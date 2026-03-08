package com.study.playground.ticket.mapper;

import com.study.playground.ticket.domain.TicketSource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TicketSourceMapper {
    List<TicketSource> findByTicketId(@Param("ticketId") Long ticketId);
    void insertBatch(@Param("ticketId") Long ticketId, @Param("sources") List<TicketSource> sources);
    void deleteByTicketId(@Param("ticketId") Long ticketId);
}
