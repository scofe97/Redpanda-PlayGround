package com.study.playground.operator.supporttool.infrastructure;

import feign.Response;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@FeignClient(name = "operator-jenkins", url = "http://placeholder")
public interface JenkinsFeignClient {

    @GetMapping("/api/json")
    String getStatus(URI baseUri, @RequestHeader("Authorization") String auth);

    @GetMapping("/crumbIssuer/api/json")
    Response getCrumb(URI baseUri, @RequestHeader("Authorization") String auth);

    @PostMapping(value = "/user/{username}/descriptorByName/jenkins.security.ApiTokenProperty/generateNewToken",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    String generateApiToken(URI baseUri,
                            @PathVariable("username") String username,
                            @RequestHeader("Authorization") String auth,
                            @RequestHeader("Jenkins-Crumb") String crumb,
                            @RequestHeader("Cookie") String cookie,
                            @RequestBody String form);

    @RequestMapping(method = RequestMethod.POST, consumes = MediaType.APPLICATION_XML_VALUE)
    void createItem(URI uri,
                    @RequestHeader("Authorization") String auth,
                    @RequestBody String configXml);

    @GetMapping
    String getItem(URI uri, @RequestHeader("Authorization") String auth);
}
