import hudson.model.Run
import hudson.model.TaskListener
import hudson.model.ParametersAction
import hudson.model.listeners.RunListener

/**
 * 전역 빌드 리스너 -- 빌드 시작/완료 시 rpk로 Kafka 토픽에 JSON 직접 발행.
 *
 * EXECUTION_JOB_ID 파라미터가 있는 빌드만 이벤트를 발송한다.
 * Operator가 JSON/Avro 듀얼 파싱으로 수신한다.
 *
 * 토픽:
 *   시작: playground.executor.events.job-started
 *   완료: playground.executor.events.job-completed
 */

def RPK_PATH = '/var/jenkins_home/rpk'
def BROKERS = System.getenv('RPK_BROKERS') ?: 'redpanda-0.redpanda.rp-oss.svc.cluster.local:9092'
def STARTED_TOPIC = System.getenv('STARTED_TOPIC') ?: 'playground.executor.events.job-started'
def COMPLETED_TOPIC = System.getenv('COMPLETED_TOPIC') ?: 'playground.executor.events.job-completed'
def MAX_RETRIES = 3

def rpkProduce(String rpkPath, String brokers, String topic, String key, String payload, listener, int maxRetries) {
    int attempt = 0
    boolean sent = false
    while (attempt < maxRetries && !sent) {
        attempt++
        try {
            def cmd = ['bash', '-c', "echo '${payload}' | ${rpkPath} topic produce ${topic} --brokers ${brokers} -k ${key}"]
            def proc = cmd.execute()
            proc.waitFor()
            if (proc.exitValue() == 0) {
                sent = true
                listener?.logger?.println("[WEBHOOK-RPK] Sent to ${topic} (attempt ${attempt}): ${payload.take(100)}...")
            } else {
                def stderr = proc.errorStream.text
                listener?.logger?.println("[WEBHOOK-RPK] Retry ${attempt}/${maxRetries} on ${topic}: ${stderr}")
                if (attempt < maxRetries) Thread.sleep(1000 * attempt)
            }
        } catch (Exception e) {
            listener?.logger?.println("[WEBHOOK-RPK] Retry ${attempt}/${maxRetries} on ${topic}: ${e.message}")
            if (attempt < maxRetries) Thread.sleep(1000 * attempt)
        }
    }
    return sent
}

RunListener.all().add(new RunListener<Run>() {

    @Override
    void onStarted(Run run, TaskListener listener) {
        def paramsAction = run.getAction(ParametersAction)
        def executionJobId = paramsAction?.getParameter('EXECUTION_JOB_ID')?.value
        if (!executionJobId) return

        def jobId   = paramsAction?.getParameter('JOB_ID')?.value ?: '0'
        def jobName = run.parent.fullName

        def payload = """{"executionJobId":${executionJobId},"jobId":${jobId},"result":"STARTED","buildNumber":${run.number},"jobName":"${jobName}","duration":0,"url":""}"""

        def sent = rpkProduce(RPK_PATH, BROKERS, STARTED_TOPIC, executionJobId.toString(), payload, listener, MAX_RETRIES)
        if (!sent) {
            listener?.logger?.println("[WEBHOOK-RPK] Failed to send started event after ${MAX_RETRIES} retries: executionJobId=${executionJobId}")
        }
    }

    @Override
    void onFinalized(Run run) {
        def paramsAction = run.getAction(ParametersAction)
        def executionJobId = paramsAction?.getParameter('EXECUTION_JOB_ID')?.value
        if (!executionJobId) return

        def listener = run.getListener()
        def jobId       = paramsAction?.getParameter('JOB_ID')?.value ?: '0'
        def result      = run.result?.toString() ?: 'UNKNOWN'
        def buildNumber = run.number
        def jobName     = run.parent.fullName
        def duration    = run.duration
        def url         = run.absoluteUrl ?: ''

        def payload = """{"executionJobId":${executionJobId},"jobId":${jobId},"result":"${result}","buildNumber":${buildNumber},"jobName":"${jobName}","duration":${duration},"url":"${url}"}"""

        def sent = rpkProduce(RPK_PATH, BROKERS, COMPLETED_TOPIC, executionJobId.toString(), payload, listener, MAX_RETRIES)
        if (!sent) {
            listener?.logger?.println("[WEBHOOK-RPK] Failed to send completed event after ${MAX_RETRIES} retries: executionJobId=${executionJobId}")
        }
    }
})
