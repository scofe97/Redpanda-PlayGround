CREATE SCHEMA IF NOT EXISTS operator;

DROP TABLE IF EXISTS operator.job_dependency CASCADE;
DROP TABLE IF EXISTS operator.pipeline_version CASCADE;
DROP TABLE IF EXISTS operator.pipeline_step CASCADE;
DROP TABLE IF EXISTS operator.pipeline CASCADE;
DROP TABLE IF EXISTS operator.job CASCADE;
DROP TABLE IF EXISTS operator.purpose_entry CASCADE;
DROP TABLE IF EXISTS operator.purpose CASCADE;
DROP TABLE IF EXISTS operator.support_tool CASCADE;
DROP TABLE IF EXISTS operator.project CASCADE;
DROP TABLE IF EXISTS operator.outbox_event CASCADE;
DROP TABLE IF EXISTS operator.processed_event CASCADE;

ALTER TABLE IF EXISTS public.project SET SCHEMA operator;
ALTER TABLE IF EXISTS public.support_tool SET SCHEMA operator;
ALTER TABLE IF EXISTS public.purpose SET SCHEMA operator;
ALTER TABLE IF EXISTS public.purpose_entry SET SCHEMA operator;
ALTER TABLE IF EXISTS public.job SET SCHEMA operator;
ALTER TABLE IF EXISTS public.job_dependency SET SCHEMA operator;
ALTER TABLE IF EXISTS public.pipeline SET SCHEMA operator;
ALTER TABLE IF EXISTS public.pipeline_version SET SCHEMA operator;
ALTER TABLE IF EXISTS public.pipeline_step SET SCHEMA operator;
ALTER TABLE IF EXISTS public.outbox_event SET SCHEMA operator;
ALTER TABLE IF EXISTS public.processed_event SET SCHEMA operator;

ALTER TABLE IF EXISTS operator_stub.operator_job SET SCHEMA operator;
DROP TABLE IF EXISTS operator_stub.outbox_event;

DROP SCHEMA IF EXISTS operator_stub CASCADE;
