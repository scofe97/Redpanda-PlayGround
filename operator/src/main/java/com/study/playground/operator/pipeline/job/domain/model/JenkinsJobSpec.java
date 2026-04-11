package com.study.playground.operator.pipeline.job.domain.model;

/**
 * Jenkins 잡 생성에 필요한 스펙.
 * {projectId}/{presetId}/{jobId} 폴더 구조로 Jenkins에 파이프라인 잡을 생성한다.
 */
public record JenkinsJobSpec(
        String projectId
        , String presetId
        , String jobId
        , String jenkinsUrl
        , String username
        , String credential
        , String jenkinsScript
) {

    public String toPath() {
        return projectId + "/" + presetId + "/" + jobId;
    }

    public String toApiPath() {
        return "/job/" + projectId + "/job/" + presetId + "/job/" + jobId;
    }

    public String projectFolderApiPath() {
        return "/job/" + projectId;
    }

    public String presetFolderApiPath() {
        return "/job/" + projectId + "/job/" + presetId;
    }

    public String toConfigXml() {
        var script = jenkinsScript != null ? jenkinsScript : defaultPipelineScript();
        return """
                <?xml version='1.1' encoding='UTF-8'?>
                <flow-definition plugin="workflow-job">
                  <definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps">
                    <script>%s</script>
                    <sandbox>true</sandbox>
                  </definition>
                  <disabled>false</disabled>
                </flow-definition>
                """.formatted(escapeXml(script));
    }

    public static String folderConfigXml() {
        // Keep folder XML minimal. Serializing view metadata here can produce
        // owner-less AllView configs that break Jenkins folder pages at runtime.
        return """
                <?xml version='1.1' encoding='UTF-8'?>
                <com.cloudbees.hudson.plugins.folder.Folder plugin="cloudbees-folder">
                  <description>Auto-created folder</description>
                </com.cloudbees.hudson.plugins.folder.Folder>
                """;
    }

    private String defaultPipelineScript() {
        return """
                pipeline {
                    agent any
                    stages {
                        stage('Build') {
                            steps {
                                echo 'Hello from %s/%s/%s'
                            }
                        }
                    }
                }
                """.formatted(projectId, presetId, jobId);
    }

    private String escapeXml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
