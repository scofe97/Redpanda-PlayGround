package com.study.playground.operator.pipeline.job.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JenkinsJobSpecTest {

    @Test
    void folderConfigXml_shouldRemainMinimalAndExcludeViewMetadata() {
        String xml = JenkinsJobSpec.folderConfigXml();

        assertTrue(xml.contains("cloudbees-folder"));
        assertTrue(xml.contains("<description>Auto-created folder</description>"));
        assertFalse(xml.contains("<views>"));
        assertFalse(xml.contains("hudson.model.AllView"));
    }
}
