package com.study.playground.operator.supporttool.infrastructure;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.net.URI;
import java.util.Map;

@FeignClient(name = "support-tool-probe", url = "http://placeholder")
public interface SupportToolProbeFeignClient {

    @GetMapping
    String get(URI uri, @RequestHeader Map<String, String> headers);
}
