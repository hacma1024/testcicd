def deployToProduction() {
    def gitlabBranch = env.gitlabBranch
    echo "Branch : ${gitlabBranch}"
    def semantic_version = gitlabBranch.split("/")[2].split("\\.")
    env.config_git_branch = "${semantic_version[0]}.${semantic_version[1]}"
    echo "Config git branch: ${env.config_git_branch}"
    env.DEPLOY_RESULT_DESCRIPTION += "<h4>Test & Verify Phase Result</h4>"
    updateGitlabCommitStatus name: "deploy to production ", state: 'running'

    env.DEPLOY_RESULT_DESCRIPTION += "<h4>Deploy Phase Result</h4>"
    updateGitlabCommitStatus name: "deploy to production ", state: 'running'
    def deployInput = ""
    def deployer = ""

    def deployResultTitle = "Deploy ioc-firebase-service project with TAG ${env.config_git_branch} "
    def deployResultDescription = "<h4>Please check if pre production is accepted and decide " +
            "deploy this version to production environment or not by select at:</h4>" +
            "<h2><i><a href='${env.BUILD_URL}display/redirect'>" +
            "Deploy Process Details...</a></i></h2>" +
            "<h4>Deploy to production process will be aborted after 24 hours from this message.</h4>"
    stage("1. Create Issue in Gitlab") {
        createIssueAndMentionMaintainer(deployResultTitle, deployResultDescription)
    }
    stage("2. Wait for maintainer accept or reject to deploy to production") {
        try {
            deployer = env.project_maintainer_list
            echo "project_maintainer_list: ${env.project_maintainer_list}"
            timeout(time: 24, unit: 'HOURS') {
                deployInput = input(
                        submitter: "${deployer}",
                        submitterParameter: 'submitter',
                        message: 'Pause for wait maintainer selection', ok: "Deploy", parameters: [
                        string(defaultValue: '',
                                description: 'Version to Deploy',
                                name: 'Deploy')
                ])
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
    def versionProject = deployInput.Deploy
    env.versionProject = versionProject.trim()
    echo "Deploy Version: $env.versionProject"

    if (deployer.contains(deployInput.submitter)) {
        stage("3. Checkout Source Code") {
            jenkinsfile_utils.checkoutSourceCode("PUSH")
            def folder = sh(script: 'pwd', returnStdout: true)
            env.buildFolderResultDeploy = folder.trim()
            def commitIdStdOut = sh(script: 'git rev-parse HEAD', returnStdout: true)
            env.DEPLOY_GIT_COMMIT_ID = commitIdStdOut.trim()
        }
        stage('4. Deploy to Productions') {

            echo "Tag: ${env.versionProject}"
            sh """
                    cd cicd/deploy
                    ls -la
                    sed -i -e s/IMAGE_VERSION/${env.versionProject}/ deployment_file_production.yml
                    sudo kubectl -n scp apply -f deployment_file_production.yml --kubeconfig=config
                """

            sleep(180)
            sh """sudo kubectl -n scp get pods,svc --kubeconfig=cicd/deploy/config"""
        }
        currentBuild.result = "SUCCESS"
    } else {
        stage("Cancel deploy process") {
            echo "Version: $env.versionProject"
            echo "Deploy process is canceled."
            currentBuild.result = "ABORTED"
        }
    }
}

def rollBackTag() {
    stage("1. Get ENV Productions To Rollback") {
        sh '''
            pwd
            mkdir config-file
            cd config-file
        '''
        dir('config-file') {
            checkout changelog: true, poll: true, scm: [
                    $class                           : 'GitSCM',
                    branches                         : [[name: "master"]],
                    doGenerateSubmoduleConfigurations: false,
                    extensions                       : [[$class: 'UserIdentity',
                                                         email : 'hienptt22@viettel.com.vn', name: 'hienptt22'],
                                                        [$class: 'CleanBeforeCheckout']],
                    submoduleCfg                     : [],
                    userRemoteConfigs                : [[credentialsId: "63265de3-8396-40f9-803e-5cd0b694e519",
                                                         url          : "${env.config_productions}"]]
            ]
        }
        sleep(5)
        sh '''
            ls -la
            cp config-file/production-config.yml .
            rm -rf config-file
            cat production-config.yml
        '''
    }
    def rollbacker = ""
    def config = readYaml file: "production-config.yml"
    env.rollback_list = config['rollback_list']
    env.ip_productions = config['ip_productions']
    echo "Deploy List : ${env.deployer_list}"
    echo "IP Productions : ${env.ip_productions}"
    def versionRollBack = ''
    env.DEPLOY_RESULT_DESCRIPTION += "<h4>Test & Verify Phase Result</h4>"
    stage('Wait for user submit Version to rollback') {
        try {
            rollbacker = env.rollback_list
            echo "rollbacker: ${rollbacker}"
            timeout(time: 24, unit: 'HOURS') {
                versionRollBack = input(
                        submitter: "${rollbacker}",
                        submitterParameter: 'submitter',
                        message: 'Pause for wait maintainer selection', ok: "Rollback", parameters: [
                        string(defaultValue: '',
                                description: 'Version to rollback',
                                name: 'Version')
                ])
            }
        } catch (err) {
            echo "Exception"
            def user = err.getCauses()[0].getUser()
            if ('SYSTEM' == user.toString()) { // SYSTEM means timeout.
                echo "Timeout is exceeded!"
            } else {
                echo "Aborted by: [${user}]"
            }
            versionRollBack = 'Aborted'
        }
    }
    env.GIT_TAG_ROLLBACK = versionRollBack.Version
    echo "Version: ${env.GIT_TAG_ROLLBACK}"
    // def statusCode = sh(script: "git show-ref --verify refs/tags/${env.GIT_TAG_ROLLBACK}", returnStatus: true)
    env.GIT_TAG_ROLLBACK = versionRollBack['Version']
    echo "Version: $env.GIT_TAG_ROLLBACK"

    if (rollbacker.contains(versionRollBack.submitter)) {
        stage('4. Deploy to Productions') {
            echo "Tag: ${env.GIT_TAG_ROLLBACK}"
            jenkinsfile_CI.release2k8s('config_155_160', 'kafka-deployment_155_160.yml', "${env.GIT_TAG_ROLLBACK}")
        }
        stage("5. Automations Testing after upcode") {
            jenkinsfile_CI.autoTest("${env.ip_productions}", "${env.tagsTestUpcode}")
        }
        currentBuild.result = "SUCCESS"
    } else {
        stage("Cancel deploy process") {
            echo "Version: $env.GIT_TAG_ROLLBACK"
            echo "Deploy process is canceled."
            currentBuild.result = "ABORTED"
        }
    }
}

def createIssueAndMentionMaintainer(issueTitle, issueDescription) {
    echo "issueTitle: ${issueTitle}"
    echo "issueDescription: ${issueDescription}"
    withCredentials([usernamePassword(credentialsId: 'a5eedd9f-332d-4575-9756-c358bbd808eb', usernameVariable: 'user',
            passwordVariable: 'password')]) {
        def issueContentJson = """
                                    {
                                        "title": "${issueTitle}",
                                        "description": "${issueDescription}",
                                        "labels": "Deploy Result"
                                    }
                                """
        echo "issueContentJson: ${issueContentJson}"
        def createIssueResp = httpRequest([
                acceptType   : 'APPLICATION_JSON',
                httpMode     : 'POST',
                contentType  : 'APPLICATION_JSON',
                customHeaders: [[name: "PRIVATE-TOKEN", value: password]],
                url          : "${env.GITLAB_PROJECT_API_URL}/issues",
                requestBody  : issueContentJson

        ])
        def notifyMemberLevel = 40
        def projectMemberList = jenkinsfile_utils.getProjectMember(notifyMemberLevel)
        def issueCommentStr = ""
        for (member in projectMemberList) {
            issueCommentStr += "@${member} "
        }
        def issueCreated = jenkinsfile_utils.jsonParse(createIssueResp.content)
        def issueCommentJson = """
                                    {
                                        "body": "${issueCommentStr}"
                                    }
                                """
        httpRequest([
                acceptType   : 'APPLICATION_JSON',
                httpMode     : 'POST',
                contentType  : 'APPLICATION_JSON',
                customHeaders: [[name: "PRIVATE-TOKEN", value: password]],
                url          : "${env.GITLAB_PROJECT_API_URL}/issues/${issueCreated["iid"]}/notes",
                requestBody  : issueCommentJson
        ])
    }
}

def toList(value) {
    return [value].flatten().findAll { it != null }
}

return [
//        buildPushCommit   : this.&buildPushCommit,
//        buildMergeRequest : this.&buildMergeRequest,
        deployToProduction: this.&deployToProduction,
        rollBackTag       : this.&rollBackTag
]
