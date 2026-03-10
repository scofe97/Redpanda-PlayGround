import hudson.model.Run
import hudson.model.TaskListener
import hudson.model.ParametersAction
import hudson.model.listeners.RunListener

/**
 * 전역 웹훅 리스너 — 모든 파이프라인 완료 시 Redpanda Connect로 결과 전송.
 *
 * EXECUTION_ID 파라미터가 있는 빌드만 웹훅을 발송한다.
 * 사용자 Jenkinsfile에 웹훅 코드를 넣을 필요 없음.
 */

def WEBHOOK_URL = 'http://connect:4197/webhook/jenkins'

RunListener.all().add(new RunListener<Run>() {

    @Override
    void onCompleted(Run run, TaskListener listener) {

        // --- 1. 파라미터 추출 (EXECUTION_ID 없으면 skip) ---

        def paramsAction = run.getAction(ParametersAction)
        def executionId  = paramsAction?.getParameter('EXECUTION_ID')?.value
        if (!executionId) return

        def stepOrder  = paramsAction?.getParameter('STEP_ORDER')?.value ?: '0'

        // --- 2. 빌드 메타데이터 수집 ---

        def result      = run.result?.toString() ?: 'UNKNOWN'
        def buildNumber = run.number
        def jobName     = run.parent.fullName
        def duration    = run.duration
        def url         = run.absoluteUrl ?: ''

        // --- 3. JSON 페이로드 구성 ---

        def payload = """\
            {
              "executionId": "${executionId}",
              "stepOrder":   ${stepOrder},
              "result":      "${result}",
              "buildNumber": ${buildNumber},
              "jobName":     "${jobName}",
              "duration":    ${duration},
              "url":         "${url}"
            }""".stripIndent()

        // --- 4. 웹훅 전송 ---

        try {
            def conn = new URL(WEBHOOK_URL).openConnection() as HttpURLConnection
            conn.requestMethod  = 'POST'
            conn.connectTimeout = 5000
            conn.readTimeout    = 5000
            conn.doOutput       = true
            conn.setRequestProperty('Content-Type', 'application/json')

            conn.outputStream.withWriter('UTF-8') { it.write(payload) }

            def responseCode = conn.responseCode
            listener.logger.println(
                "[WEBHOOK] Sent to Connect (HTTP ${responseCode}): ${result}"
            )
        } catch (Exception e) {
            listener.logger.println(
                "[WEBHOOK] delivery failed (Connect may be down): ${e.message}"
            )
        }
    }
})
