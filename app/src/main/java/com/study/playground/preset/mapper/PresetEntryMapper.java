package com.study.playground.preset.mapper;

import com.study.playground.preset.domain.PresetEntry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PresetEntryMapper {
    List<PresetEntry> findByPresetId(@Param("presetId") Long presetId);
    void insert(PresetEntry entry);
    void deleteByPresetId(@Param("presetId") Long presetId);
}
