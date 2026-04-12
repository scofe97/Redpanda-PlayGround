package com.study.playground.executor.execution.infrastructure.jenkins;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("JenkinsRemoteApiClient 단위 테스트")
class JenkinsRemoteApiClientTest {

    @Mock
    JenkinsFeignClient feignClient;

    @Test
    @DisplayName("totalExecutors가 activeCount보다 크면 최대 개수를 그대로 반환해야 한다")
    void queryMaxExecutors_shouldReturnTotalExecutors() {
        var client = new JenkinsRemoteApiClient(feignClient, new ObjectMapper());
        var baseUri = URI.create("http://jenkins");

        given(feignClient.getComputerStatus(baseUri, "Basic token"))
                .willReturn("""
                        {"busyExecutors":1,"totalExecutors":3}
                        """);

        int result = client.queryMaxExecutors(1L, baseUri, "Basic token", 10, 1);

        assertThat(result).isEqualTo(3);
        verify(feignClient, never()).getComputerClasses(baseUri, "Basic token");
    }

    @Test
    @DisplayName("totalExecutors가 0이고 K8S Jenkins면 애플리케이션 설정값을 반환해야 한다")
    void queryMaxExecutors_zeroExecutorsOnK8s_shouldReturnConfiguredCapacity() {
        var client = new JenkinsRemoteApiClient(feignClient, new ObjectMapper());
        var baseUri = URI.create("http://jenkins");

        given(feignClient.getComputerStatus(baseUri, "Basic token"))
                .willReturn("""
                        {"busyExecutors":0,"totalExecutors":0}
                        """);
        given(feignClient.getComputerClasses(baseUri, "Basic token"))
                .willReturn("""
                        {
                          "totalExecutors":0,
                          "computer":[
                            {
                              "_class":"org.csanchez.jenkins.plugins.kubernetes.KubernetesSlave",
                              "assignedLabels":[{"name":"k8s-build"}]
                            }
                          ]
                        }
                        """);

        int result = client.queryMaxExecutors(1L, baseUri, "Basic token", 7, 0);

        assertThat(result).isEqualTo(7);
    }

    @Test
    @DisplayName("totalExecutors와 activeCount가 같고 K8S label이 있으면 애플리케이션 설정값을 반환해야 한다")
    void queryMaxExecutors_fullExecutorsOnK8s_shouldReturnConfiguredCapacity() {
        var client = new JenkinsRemoteApiClient(feignClient, new ObjectMapper());
        var baseUri = URI.create("http://jenkins");

        given(feignClient.getComputerStatus(baseUri, "Basic token"))
                .willReturn("""
                        {"busyExecutors":2,"totalExecutors":2}
                        """);
        given(feignClient.getComputerClasses(baseUri, "Basic token"))
                .willReturn("""
                        {
                          "totalExecutors":2,
                          "computer":[
                            {
                              "_class":"hudson.slaves.DumbSlave",
                              "assignedLabels":[{"name":"linux-k8s-agent"}]
                            }
                          ]
                        }
                        """);

        int result = client.queryMaxExecutors(1L, baseUri, "Basic token", 5, 2);

        assertThat(result).isEqualTo(5);
    }

    @Test
    @DisplayName("totalExecutors와 activeCount가 같아도 K8S가 아니면 최대 개수를 그대로 반환해야 한다")
    void queryMaxExecutors_fullExecutorsOnStaticNode_shouldReturnTotalExecutors() {
        var client = new JenkinsRemoteApiClient(feignClient, new ObjectMapper());
        var baseUri = URI.create("http://jenkins");

        given(feignClient.getComputerStatus(baseUri, "Basic token"))
                .willReturn("""
                        {"busyExecutors":2,"totalExecutors":2}
                        """);
        given(feignClient.getComputerClasses(baseUri, "Basic token"))
                .willReturn("""
                        {
                          "totalExecutors":2,
                          "computer":[
                            {
                              "_class":"hudson.slaves.DumbSlave",
                              "assignedLabels":[{"name":"linux"}]
                            }
                          ]
                        }
                        """);

        int result = client.queryMaxExecutors(1L, baseUri, "Basic token", 5, 2);

        assertThat(result).isEqualTo(2);
    }
}
