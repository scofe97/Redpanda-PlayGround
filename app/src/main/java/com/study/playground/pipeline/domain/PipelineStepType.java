package com.study.playground.pipeline.domain;

public enum PipelineStepType {
    GIT_CLONE,
    BUILD,
    ARTIFACT_DOWNLOAD,
    IMAGE_PULL,
    DEPLOY
}
