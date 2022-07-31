import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import java.text.DecimalFormat
import hudson.tasks.test.AbstractTestResultAction
import groovy.json.*

jenkinsfile_BlueGreen = load 'jenkinsfile_BlueGreen.groovy'

//functions scan source code
def getSonarQubeAnalysisResult(sonarQubeURL, projectKey) {
    def metricKeys = "bugs,vulnerabilities,code_smells"
    def measureResp = httpRequest([
            acceptType : 'APPLICATION_JSON',
            httpMode   : 'GET',
            contentType: 'APPLICATION_JSON',
            url        : "${sonarQubeURL}/api/measures/component?metricKeys=${metricKeys}&component=${projectKey}"
    ])
    def measureInfo = jenkinsfile_utils.jsonParse(measureResp.content)
    def metricResultList = measureInfo['component']['measures']
    echo "${metricResultList}"
    int bugsEntry = getMetricEntryByKey(metricResultList, "bugs")['value'] as Integer
    int vulnerabilitiesEntry = getMetricEntryByKey(metricResultList, "vulnerabilities")['value'] as Integer
    int codeSmellEntry = getMetricEntryByKey(metricResultList, "code_smells")['value'] as Integer
    return ["bugs": bugsEntry, "vulnerabilities": vulnerabilitiesEntry, "code_smells": codeSmellEntry]
}

def getMetricEntryByKey(metricResultList, metricKey) {
    for (metricEntry in metricResultList) {
        if (metricEntry["metric"] == metricKey) {
            echo "${metricEntry}"
            return metricEntry
        }
    }
    return null
}

@NonCPS
def genSonarQubeProjectKey() {
    def sonarqubeProjectKey = ""
    if ("${env.gitlabActionType}".toString() == "PUSH" || "${env.gitlabActionType}".toString() == "TAG_PUSH") {
        sonarqubeProjectKey = "${env.groupName}:${env.gitlabSourceRepoName}:${env.gitlabSourceBranch}"
    } else if ("${env.gitlabActionType}".toString() == "MERGE" || "${env.gitlabActionType}".toString() == "NOTE") {
        sonarqubeProjectKey = "MR-${env.gitlabSourceRepoName}:${env.gitlabSourceBranch}-to-" +
                "${env.gitlabTargetBranch}"
    }
    return sonarqubeProjectKey.replace('/', '-')
}

@NonCPS
def getTestResultFromJenkins() {
    def testResult = [:]
    AbstractTestResultAction testResultAction = currentBuild.rawBuild.getAction(AbstractTestResultAction.class)
    testResult["total"] = testResultAction.totalCount
    testResult["failed"] = testResultAction.failCount
    testResult["skipped"] = testResultAction.skipCount
    testResult["passed"] = testResultAction.totalCount - testResultAction.failCount - testResultAction.skipCount
    return testResult
}

@NonCPS
def getProjectCodeCoverageInfo(coverageInfoXmlStr) {
    def coverageInfoXml = jenkinsfile_utils.parseXml(coverageInfoXmlStr)
    def coverageInfoStr = ""
    coverageInfoXml.counter.each {
        def coverageType = it.@type as String
        int missed = (it.@missed as String) as Integer
        int covered = (it.@covered as String) as Integer
        int total = missed + covered

        def coveragePercent = 0.00
        if (total > 0) {
            coveragePercent = Double.parseDouble(
                    new DecimalFormat("###.##").format(covered * 100.0 / total))
        }
        coverageInfoStr += "- <b>${coverageType}</b>: <i>${covered}</i>/<i>${total}</i> (<b>${coveragePercent}%</b>)<br/>"
    }
    return coverageInfoStr
}

def unitTestAndCodeCoverage(buildType) {
    stage("3.1 Checkout source code") {
        jenkinsfile_utils.checkoutSourceCode(buildType)
    }
    stage("3.2 Unit Test & Code Coverage") {
        try {
            sh """
            mvn clean test org.jacoco:jacoco-maven-plugin:0.8.5:report-aggregate
            """
            echo "code coverage done"
            jacoco([
                    classPattern : 'target/classes',
                    sourcePattern: 'src/main/java'
            ])
            def coverageResultStrComment = "<b>Coverage Test Result:</b> <br/><br/>"
            def coverageInfoXmlStr = readFile "target/jacoco-aggregate-report/jacoco.xml"
            echo "Coverage Info: ${getProjectCodeCoverageInfo(coverageInfoXmlStr)} "
            coverageResultStrComment += getProjectCodeCoverageInfo(coverageInfoXmlStr)
            coverageResultStrComment += "<i><a href='${env.BUILD_URL}Code-Coverage-Report/jacoco'>" +
                    "Details Code Coverage Test Report...</a></i><br/><br/>"
            env.CODE_COVERAGE_RESULT_STR = coverageResultStrComment
        } catch (err) {
            echo "Error when test Unit Test"
            throw err
        } finally {
            sh 'ls -al'
            //junit '*/target/*-results/test/TEST-*.xml'
            junit 'target/surefire-reports/TEST-*.xml'
            def unitTestResult = getTestResultFromJenkins()

            env.UNIT_TEST_PASSED = unitTestResult["passed"]
            env.UNIT_TEST_FAILED = unitTestResult["failed"]
            env.UNIT_TEST_SKIPPED = unitTestResult["skipped"]
            env.UNIT_TEST_TOTAL = unitTestResult["total"]

            def testResultContent = "- Passed: <b>${unitTestResult['passed']}</b> <br/>" +
                    "- Failed: <b>${unitTestResult['failed']}</b> <br/>" +
                    "- Skipped: <b>${unitTestResult['skipped']}</b> <br/>"

            def testResultString = "<b> Unit Test Result:</b> <br/><br/>${testResultContent} " +
                    "<i><a href='${env.BUILD_URL}testReport/'>Details Unit Test Report...</a></i><br/><br/>"
            env.UNIT_TEST_RESULT_STR = testResultString

            if (unitTestResult['failed'] > 0) {
                error "Failed ${unitTestResult['failed']} unit tests"
                env.UNIT_TEST_RESULT_STR += "Failed ${unitTestResult['failed']} unit tests"
            }
        }
    }
}

def sonarQubeScan(buildType) {
    return
    stage("1.1 Checkout Source Code") {
        jenkinsfile_utils.checkoutSourceCode(buildType)
        echo 'Checkout source code'
    }
    stage('SonarQube analysis') {
        env.SONAR_QUBE_PROJECT_KEY = genSonarQubeProjectKey()
        withSonarQubeEnv('SONARQ_V6') {
            sh(returnStatus: true, script:
                    "/home/app/server/sonar-scanner/bin/sonar-scanner " +
                            "-Dsonar.projectName=${env.SONAR_QUBE_PROJECT_KEY} " +
                            "-Dsonar.projectKey=${env.SONAR_QUBE_PROJECT_KEY} " +
                            "-Dsonar.java.binaries=. " +
                            "-Dsonar.sources=. " +
                            "-Dsonar.exclusions=**/target/**,**/Libs/**"
            )
            sh 'ls -al'
            sh 'cat .scannerwork/report-task.txt'
            def props = readProperties file: '.scannerwork/report-task.txt'
            env.SONAR_CE_TASK_ID = props['ceTaskId']
            env.SONAR_PROJECT_KEY = props['projectKey']
            env.SONAR_SERVER_URL = props['serverUrl']
            env.SONAR_DASHBOARD_URL = props['dashboardUrl']

            echo "SONAR_SERVER_URL: ${env.SONAR_SERVER_URL}"
            echo "SONAR_PROJECT_KEY: ${env.SONAR_PROJECT_KEY}"
            echo "SONAR_DASHBOARD_URL: ${env.SONAR_DASHBOARD_URL}"
        }
    }

    stage("3.3. Quality Gate") {
        def qg = null
        try {
            def sonarQubeRetry = 0
            def sonarScanCompleted = false
            while (!sonarScanCompleted) {
                try {
                    sleep 10
                    timeout(time: 1, unit: 'MINUTES') {
                        script {
                            qg = waitForQualityGate()
                            sonarScanCompleted = true
                            if (qg.status != 'OK') {
                                if (env.bypass == 'true') {
                                    echo "Sonar contain error"
                                } else {
                                    error "Pipeline failed due to quality gate failure: ${qg.status}"
                                }
                            }
                        }
                    }
                } catch (FlowInterruptedException interruptEx) {
                    // check if exception is system timeout
                    if (interruptEx.getCauses()[0] instanceof org.jenkinsci.plugins.workflow.steps.TimeoutStepExecution.ExceededTimeout) {
                        if (sonarQubeRetry <= 10) {
                            sonarQubeRetry += 1
                        } else {
                            if (env.bypass == 'true') {
                                echo "Sonar contain error"
                            } else {
                                error "Cannot get result from Sonarqube server. Build Failed."
                            }
                        }
                    } else {
                        throw interruptEx
                    }
                }
                catch (err) {
                    throw err
                }
            }
        }
        catch (err) {
            throw err
        } finally {
            def codeAnalysisResult = getSonarQubeAnalysisResult(env.SONAR_SERVER_URL, env.SONAR_PROJECT_KEY)
            def sonarQubeAnalysisStr = "- Vulnerabilities: <b>${codeAnalysisResult["vulnerabilities"]}</b> <br/>" +
                    "- Bugs: <b>${codeAnalysisResult["bugs"]}</b> <br/>" +
                    "- Code Smell: <b>${codeAnalysisResult["code_smells"]}</b> <br/>"
            def sonarQubeAnalysisComment = "<b>SonarQube Code Analysis Result: ${qg.status}</b> <br/><br/>${sonarQubeAnalysisStr} " +
                    "<i><a href='${SONAR_DASHBOARD_URL}'>" +
                    "Details SonarQube Code Analysis Report...</a></i><br/><br/>"
            env.SONAR_QUBE_SCAN_RESULT_STR = sonarQubeAnalysisComment
            if ("${env.gitlabActionType}".toString() == "MERGE" || "${env.gitlabActionType}".toString() == "NOTE") {
                echo "check vulnerabilities, code smell and bugs"
                int maximumAllowedVulnerabilities = env.MAXIMUM_ALLOWED_VUNERABILITIES as Integer
                int maximumAllowedBugs = env.MAXIMUM_ALLOWED_BUGS as Integer
                int maximumAllowedCodeSmell = env.MAXIMUM_ALLOWED_CODE_SMELL as Integer
                echo "maximum allow vulnerabilities:  ${maximumAllowedVulnerabilities} "
                echo "maximum allow bugs:  ${maximumAllowedBugs}"
                echo "maximum allow code smell:  ${maximumAllowedCodeSmell}"
                if (codeAnalysisResult["vulnerabilities"] > maximumAllowedVulnerabilities ||
                        codeAnalysisResult["bugs"] > maximumAllowedBugs || codeAnalysisResult["code_smells"] > maximumAllowedCodeSmell) {
                    if (env.bypass == 'true') {
                        echo "Vulnerability, code smell or bug number overs allowed limits!"
                    } else {
                        error "Vulnerability, code smell or bug number overs allowed limits!"
                    }

                }
            }
        }
    }
}

/*
    - Build all module.
    - change module to build in def buildService
*/

def buildService(buildType, version) {
    stage("2.1 Checkout Source Code") {
        jenkinsfile_utils.checkoutSourceCode(buildType)
        echo 'Checkout source code'
    }
    stage('2.2 Build back end') {
        def dir = sh(script: 'pwd', returnStdout: true)
        env.buildFolderResult = dir.trim()
        sh "sh cicd/build.sh ${version}_u${BUILD_NUMBER}"
    }
}

def pushImageToDockerRepo(version) {
    stage("4.1 Push Image To Repo Docker") {
        jenkinsfile_utils.checkoutSourceCode("PUSH")
        echo 'Checkout source code'

        withCredentials([usernamePassword(credentialsId: "${env.HARBOR_CREDENTIALS_ID}",
                usernameVariable: 'username',
                passwordVariable: 'password')
        ]) {
            sh "sh cicd/push.sh ${username} ${password} ${version}_u${BUILD_NUMBER}"
        }
    }
}

/*
    - Config module with moduleName
    - 'check' to identify path to zip file
*/

def packageServicesAndUploadToRepo(groupId, artifactId, moduleName) {

    stage('Upload artifact to Nexus server') {
        def uploadSuccessComment = "<b>Build & package Artifact Results - " +
                "Build Artifact module ${moduleName} is created. "
        nexusArtifactUploader artifacts: [[artifactId: "${artifactId}_${moduleName}",
                                           classifier: '',
                                           file      : "target/registry.jar",
                                           type      : 'jar']],
                credentialsId: "${env.NEXUS_CREDENTIALS_ID}",
                groupId: "${groupId}",
                nexusUrl: '10.60.156.26:8081',
                nexusVersion: 'nexus3',
                protocol: 'http',
                repository: 'msbuild',
                version: "1.${BUILD_NUMBER}"
        env.PACKAGE_UPLOAD_IMAGE_RESULT_STR = uploadSuccessComment
    }
}

def release2k8s(version, environment) {
    checkout changelog: true, poll: true, scm: [
            $class                           : 'GitSCM',
            branches                         : [[name: "develop"]],
            doGenerateSubmoduleConfigurations: false,
            extensions                       : [[$class: 'UserIdentity',
                                                 email : 'chuongnp@viettel.com.vn', name: 'chuongnp'],
                                                [$class: 'CleanBeforeCheckout']],
            submoduleCfg                     : [],
            userRemoteConfigs                : [[credentialsId: "${env.GITLAB_CREDENTIALS_ID}",
                                                 url          : "${env.gitlabSourceRepoHomepage}" + ".git"]]
    ]
    sleep(5)

    sh "sh cicd/deploy.sh ${version}_u${BUILD_NUMBER} ${environment}"

    sleep(60)
}

/*
    - Config các stage run when push commit
    - SonarQube
    - Build
    - Deploy
*/

def buildPushCommit() {

    echo "gitlabBranch: $env.gitlabBranch"
    def crmBackendPom = readMavenPom([file: "pom.xml"])
    def crmBackendVersion = crmBackendPom.getVersion()
    echo " Version project : ${crmBackendVersion}_${env.gitlabBranch}"
    def tasks = [:]
    tasks['SonarQubeScan'] = {
        node("$env.node_slave") {
            echo "test sonar"
            sonarQubeScan("PUSH")
        }
    }

    tasks['Package and Build Artifact'] = {
        node("$env.node_slave") {
            stage('2. Build Package & Image Docker') {
                buildService("PUSH", "${crmBackendVersion}_${env.gitlabBranch}")
            }
        }
    }

    tasks['3. unitTestAndCodeCoverage'] = {
        node("$env.node_slave") {
            unitTestAndCodeCoverage("PUSH")
        }
    }

    parallel tasks


    def uploads = [:]
    def deploys = [:]

    if (env.gitlabBranch == env.RELEASE_BRANCH) {

    } else if (env.gitlabBranch == env.STAGING_BRANCH) {

        uploads['4. Push Image To Repo Docker'] = {
            node("$env.node_slave") {
                pushImageToDockerRepo("${crmBackendVersion}_${env.gitlabBranch}")
            }
        }
        parallel uploads

//        deploys['6. Deploy to K8s staging'] = {
//            node("$env.node_slave") {
//                stage('Deploy to K8s') {
//                    echo 'deploy to K8s'
//                    release2k8s("${crmBackendVersion}_${env.gitlabBranch}", "staging")
//                }
//            }
//        }
//        parallel deploys

        stage("5. Deploy to Release") {
            node("$env.node_slave") {
                jenkinsfile_BlueGreen.deployToPreRelease("${crmBackendVersion}_${env.gitlabBranch}_u${BUILD_NUMBER}", "release", "ioc-integration-service")
            }
        }
        parallel deploys

        def deployInput = ""
        def deployer = ""
        stage("6. Wait for maintainer accept or reject to transfer traffic from production to deploy") {
            try {
                deployer = env.project_maintainer_list
                echo "project_maintainer_list: ${env.project_maintainer_list}"

                timeout(time: 24, unit: 'HOURS') {
                    deployInput = input(
                            submitter: "${deployer}",
                            submitterParameter: 'submitter',
                            message: 'Transfer traffic to pre release ', ok: "Transfer")
                }
            } catch (err) { // timeout reached or input false
                echo "Exception"
                def user = err.getCauses()[0].getUser()
                if ('SYSTEM' == user.toString()) { // SYSTEM means timeout.
                    echo "Timeout is exceeded!"
                } else {
                    echo "Aborted by: [${user}]"
                }
                deployInput = "Abort"
            }
            echo "Input value: $deployInput"
        }

        if (deployer.contains(deployInput)) {
            stage("7. Transfer traffic to Release version") {
                node("$env.node_slave") {
                    jenkinsfile_BlueGreen.transferTraffic("release", "ioc-integration-service", "${crmBackendVersion}_${env.gitlabBranch}_u${BUILD_NUMBER}")
                }
            }
            stage("8. Rollback or Delete Old Version") {
                jenkinsfile_BlueGreen.rollBackOrDeleteOldVersion("release", "ioc-integration-service", "${crmBackendVersion}_${env.gitlabBranch}_u${BUILD_NUMBER}");
            }
        } else {
            stage("9. Cancel transfer process") {
                echo "Version: ${env.project_version}"
                echo "Transfer process is canceled."
            }
        }

    } else if (env.gitlabBranch == env.DEV_BRANCH) {
        uploads['4. Push Image To Repo Docker'] = {
            node("$env.node_slave") {
                stage('5. Push Image To Repo Docker') {
                    pushImageToDockerRepo("${crmBackendVersion}_${env.gitlabBranch}")
                }
            }
        }
        parallel uploads

        deploys['5. Deploy to K8s testing'] = {
            node("$env.node_slave") {
                stage('6. Deploy to K8s') {
                    echo 'deploy to K8s'
                    release2k8s("${crmBackendVersion}_${env.gitlabBranch}", "dev")
                }
            }
        }
        parallel deploys
    }

    currentBuild.result = "SUCCESS"

}

return [
        buildPushCommit               : this.&buildPushCommit,
        buildMergeRequest             : this.&buildMergeRequest,
        buildAcceptAndCloseMR         : this.&buildAcceptAndCloseMR,
        sonarQubeScan                 : this.&sonarQubeScan,
        buildService                  : this.&buildService,
        deploy_module_web             : this.&deploy_module_web,
        packageServicesAndUploadToRepo: this.&packageServicesAndUploadToRepo

]

//
//def buildMergeRequest() {
//    echo "gitlabBranch: $env.gitlabBranch"
//    def crmBackendPom = readMavenPom([file: "pom.xml"])
//    def crmBackendVersion = crmBackendPom.getVersion()
//    echo " Version project : ${crmBackendVersion}_${env.gitlabBranch}"
//    def tasks = [:]
//
//    tasks['SonarQubeScan'] = {
//        node("$env.node_slave") {
//            echo "test sonar"
//            sonarQubeScan("MERGE")
//        }
//    }
//
//    tasks['Package and Build Artifact'] = {
//        node("$env.node_slave") {
//            stage('Build Package & Image Docker') {
//                buildService("MERGE", "${crmBackendVersion}_${env.gitlabBranch}")
//            }
//        }
//    }
//
//    tasks['unitTestAndCodeCoverage'] = {
//        node("$env.node_slave") {
//            unitTestAndCodeCoverage("MERGE")
//        }
//    }
//
//    parallel tasks
//
//    def uploads = [:]
//    def deploys = [:]
//    if (env.gitlabBranch == env.STAGING_BRANCH) {
//
//        uploads['Push Image To Repo Docker'] = {
//            node("$env.node_slave") {
//                stage('Push Image To Repo Docker') {
//                    pushImageToDockerRepo("${crmBackendVersion}_${env.gitlabBranch}")
//                }
//            }
//        }
//        parallel uploads
//
//        deploys['Deploy to K8s staging'] = {
//            node("$env.node_slave") {
//                stage('Deploy to K8s') {
//                    echo 'deploy to K8s'
//                    release2k8s("${crmBackendVersion}_${env.gitlabBranch}", "staging")
//                }
//            }
//        }
//        parallel deploys
//
//    } else {
//
//        uploads['Push Image To Repo Docker'] = {
//            node("$env.node_slave") {
//                stage('Push Image To Repo Docker') {
//                    pushImageToDockerRepo("${crmBackendVersion}_${env.gitlabBranch}")
//                }
//            }
//        }
//        parallel uploads
//
//        deploys['Deploy to K8s testing'] = {
//            node("$env.node_slave") {
//                stage('Deploy to K8s') {
//                    echo 'deploy to K8s'
//                    release2k8s("${crmBackendVersion}_${env.gitlabBranch}", "dev")
//                }
//            }
//        }
//        parallel deploys
//    }
//
//    currentBuild.result = "SUCCESS"
//}


/*
  Sửa các stage cho phù hợp với dự án
*/

//def deployToPreRelease(version, enviroment, service) {
//    def currentTestColor = getCurrentTestColor(service, "ioc", enviroment)
//    echo "currentTestColor: $currentTestColor"
//    def testColor = "$currentTestColor".trim()
//    def activeColor
//    if (testColor == "blue") {
//        activeColor = "green"
//    } else {
//        testColor = "green"
//        activeColor = "blue"
//    }
//    env.ACTIVE_COLOR = activeColor
//    env.TEST_COLOR = testColor
//    try {
//        sh """
//			sh cicd/${enviroment}/deploy.sh ${version} ${enviroment} ${service} ${testColor}
//            sleep 20
//        """
//
//        dir("${env.buildFolderResult}/cicd/${enviroment}") {
//            echo "Get Pods, service detail"
//            sh """
//            	kubectl -n ioc get pods,svc --kubeconfig=k8s-config
//            """
//            def checkProcessRunning = sh(returnStdout: true, script: "kubectl -n ioc --kubeconfig=k8s-config get pods --sort-by=.status.startTime | grep '${service}' | tail -n 1 | awk '{print \$3}'").trim()
//            echo "checkProcessRunning: $checkProcessRunning ${service}"
//            if (checkProcessRunning == "Running") {
//                env.STAGING_PORT = sh(returnStdout: true, script: "kubectl -n ioc --kubeconfig=k8s-config get svc | grep '${service}' | awk '{print \$5}' | grep -o '[[:digit:]]*' | tail -n 1").trim()
//                echo "port: $env.STAGING_PORT"
//                env.STAGING_IP = sh(returnStdout: true, script: "kubectl -n ioc --kubeconfig=k8s-config get node -o wide | head -2 | tail -1 | awk '{print \$6'}").trim()
//                echo "ip: $env.STAGING_IP"
//            } else {
//                error "Deploy service ${service} version ${version} to k8s ${enviroment} failure open port $env.STAGING_PORT"
//            }
//        }
//    } catch (err) {
//        error "Deploy to k8s failure"
//    }
//}
//
//def getCurrentTestColor(service, namespace, enviroment) {
//    def log = sh(script: 'cd cicd/' + enviroment + '; if kubectl get virtualservice ' + service + ' -n ' + namespace + ' --kubeconfig=k8s-config; then CURRENT_TEST_COLOR=$(kubectl get virtualservice ' + service + ' -n ' + namespace + ' --kubeconfig=k8s-config -o json | jq -r \'.spec.http[0].route[0].destination.subset\'); echo $CURRENT_TEST_COLOR; fi', returnStdout: true)
//    echo "log: $log"
//    def currentTestColor
//    if (log.contains("blue")) {
//        currentTestColor = "blue"
//    } else if (log.contains("green")) {
//        currentTestColor = "green"
//    } else {
//        echo "No Color Enviroment found, choose default enviroment: blue"
//        currentTestColor = "blue"
//    }
//    echo "test version in enviroment: $currentTestColor "
//    return currentTestColor;
//}
//
//def transferTraffic(enviroment, service, version) {
//    echo "Transfer traffic from $env.ACTIVE_COLOR to $env.TEST_COLOR"
//    try {
//        def activeColor = env.TEST_COLOR
//        def testColor = env.ACTIVE_COLOR
//        sh """
//            pwd
//            cd cicd/${enviroment}
//            ls -la
//            sh update-route.sh  ${activeColor} ${testColor} ${service}
//            kubectl -n ioc apply -f ${service}-route* --kubeconfig=k8s-config
//            sleep 20
//        """
//        dir("${env.buildFolderResult}/cicd/${enviroment}") {
//            echo "Get Pods, service detail"
//            sh """
//            	kubectl -n ioc get pods,svc --kubeconfig=k8s-config
//            """
//            def checkProcessRunning = sh(returnStdout: true, script: "kubectl -n ioc --kubeconfig=k8s-config get pods --sort-by=.status.startTime | grep '${service}' | tail -n 1 | awk '{print \$3}'").trim()
//            echo "checkProcessRunning: $checkProcessRunning ${service}"
//            if (checkProcessRunning == "Running") {
//                env.STAGING_PORT = sh(returnStdout: true, script: "kubectl -n ioc --kubeconfig=k8s-config get svc | grep '${service}' | awk '{print \$5}' | grep -o '[[:digit:]]*' | tail -n 1").trim()
//                echo "port: $env.STAGING_PORT"
//                env.STAGING_IP = sh(returnStdout: true, script: "kubectl -n ioc --kubeconfig=k8s-config get node -o wide | head -2 | tail -1 | awk '{print \$6'}").trim()
//                echo "ip: $env.STAGING_IP"
//            } else {
//                error "Deploy service ${service} version ${version} to k8s ${enviroment} failure open port $env.STAGING_PORT"
//            }
//        }
//        env.ACTIVE_COLOR = activeColor
//        env.TEST_COLOR = testColor
//        echo "Transfer traffic success, ACTIVE_COLOR: $env.ACTIVE_COLOR, TEST_COLOR: $env.TEST_COLOR"
//    } catch (err) {
//        sh """
//            pwd
//            cd cicd/${enviroment}
//            ls -la
//            sh update-route.sh  ${env.ACTIVE_COLOR} ${env.TEST_COLOR} ${service}
//            kubectl -n ioc apply -f ${service}-route* --kubeconfig=k8s-config
//            sleep 20
//        """
//        error "transfer failure"
//    }
//}
//
//def rollBackOrDeleteOldVersion(enviroment, service, version) {
//    def deployInput = "none"
//    try {
//        deployer = env.project_maintainer_list
//        echo "project_maintainer_list: ${env.project_maintainer_list}"
//
//        timeout(time: 24, unit: 'HOURS') {
//            deployInput = input(
//                    submitter: "${deployer}",
//                    submitterParameter: 'submitter',
//                    message: 'Rollback or Delete old version', ok: "Process", parameters: [choice(choices: ['rollback', 'delete', 'none'], description: '', name: 'rollbackordelete')])
//        }
//    } catch (err) { // timeout reached or input false
//        echo "Exception"
//        def user = err.getCauses()[0].getUser()
//        if ('SYSTEM' == user.toString()) { // SYSTEM means timeout.
//            echo "Timeout is exceeded!"
//        } else {
//            echo "Aborted by: [${user}]"
//        }
//        deployInput['rollbackordelete'] = 'none'
//    }
//    env.rollbackordelete = deployInput['rollbackordelete']
//    echo "Input value: $deployInput"
//
//    if (env.rollbackordelete == "rollback") {
//        echo "transfer traffic from $env.ACTIVE_COLOR to $env.TEST_COLOR"
//        transferTraffic(enviroment, service, version)
//    } else if (env.rollbackordelete == "delete") {
//        echo "Delete old version in $env.TEST_COLOR enviroment"
//        def folder = sh(script: 'pwd', returnStdout: true)
//        env.buildFolderResult = folder.trim()
//        def testColor = env.TEST_COLOR
//        sh """
//            pwd
//            cd cicd/${enviroment}
//            ls -la
//			sh update-version.sh ${testColor} ${version} ${service}
//            kubectl -n ioc delete -f ${service}-deployment* --kubeconfig=k8s-config
//            sleep 20
//        """
//        echo "Delete Success"
//    } else {
//        echo "Do nothing"
//    }
//}

