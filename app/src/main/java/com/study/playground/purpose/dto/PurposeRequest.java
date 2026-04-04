package com.study.playground.purpose.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class PurposeRequest {
    @NotBlank
    private String name;
    private String description;
    private Long projectId;
    @NotEmpty
    @Valid
    private List<EntryRequest> entries;

    @Getter
    @Setter
    public static class EntryRequest {
        @NotNull
        private String category;
        @NotNull
        private Long toolId;
    }
}
