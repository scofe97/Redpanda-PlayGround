import hudson.model.Run
import hudson.model.TaskListener
import hudson.model.ParametersAction
import hudson.model.listeners.RunListener

/**
 * 전역 빌드 리스너 -- 빌드 시작/완료 시 rpk로 Kafka 토픽에 JSON 직접 발행.
 *
 * Jenkins fullName의 마지막 세그먼트를 jobId로 사용한다.
 * Executor가 jobId + buildNumber로 ExecutionJob을 매칭한다.
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

def deriveJobId(String fullName) {
    def tokens = fullName?.tokenize('/') ?: []
    return tokens ? tokens[tokens.size() - 1] : null
}

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
        def jobName = run.parent.fullName
        def jobId = deriveJobId(jobName)
        if (!jobId) return

        def buildNumber = run.number

        def payload = """{"jobId":"${jobId}","buildNumber":${buildNumber},"result":"STARTED","jobName":"${jobName}","duration":0,"url":""}"""

        def sent = rpkProduce(RPK_PATH, BROKERS, STARTED_TOPIC, "${jobId}-${buildNumber}", payload, listener, MAX_RETRIES)
        if (!sent) {
            listener?.logger?.println("[WEBHOOK-RPK] Failed to send started event after ${MAX_RETRIES} retries: jobId=${jobId}, buildNumber=${buildNumber}")
        }
    }

    @Override
    void onFinalized(Run run) {
        def jobName = run.parent.fullName
        def jobId = deriveJobId(jobName)
        if (!jobId) return

        def listener    = run.getListener()
        def result      = run.result?.toString() ?: 'UNKNOWN'
        def buildNumber = run.number
        def duration    = run.duration
        def url         = run.absoluteUrl ?: ''

        def logContent = ''
        try {
            logContent = run.getLog(500).join('\n').replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n').replace('\r', '\\r').replace('\t', '\\t')
        } catch (Exception e) {
            listener?.logger?.println("[WEBHOOK-RPK] Failed to get log: ${e.message}")
        }

        def payload = """{"jobId":"${jobId}","buildNumber":${buildNumber},"result":"${result}","jobName":"${jobName}","duration":${duration},"url":"${url}","logContent":"${logContent}"}"""

        def sent = rpkProduce(RPK_PATH, BROKERS, COMPLETED_TOPIC, "${jobId}-${buildNumber}", payload, listener, MAX_RETRIES)
        if (!sent) {
            listener?.logger?.println("[WEBHOOK-RPK] Failed to send completed event after ${MAX_RETRIES} retries: jobId=${jobId}, buildNumber=${buildNumber}")
        }
    }
})
