

def deployToPreRelease(version, enviroment, service) {
    def currentTestColor = getCurrentTestColor(service, "ioc", enviroment)
    echo "currentTestColor: $currentTestColor"

    def testColor = "$currentTestColor".trim()
    def activeColor

    if (testColor == "blue") {
        activeColor = "green"
    } else {
        testColor = "green"
        activeColor = "blue"
    }

    env.ACTIVE_COLOR = activeColor
    env.TEST_COLOR = testColor
    try {
        sh """
			sh cicd/${enviroment}/deploy.sh ${version} ${enviroment} ${service} ${testColor}       
            sleep 20
        """

        dir("${env.buildFolderResult}/cicd/${enviroment}") {
            echo "Get Pods, service detail"
            sh """
            	kubectl -n ioc get pods,svc --kubeconfig=k8s-config
            """
            def checkProcessRunning = sh(returnStdout: true, script: "kubectl -n ioc --kubeconfig=k8s-config get pods --sort-by=.status.startTime | grep '${service}' | tail -n 1 | awk '{print \$3}'").trim()
            echo "checkProcessRunning: $checkProcessRunning ${service}"
            if (checkProcessRunning == "Running") {
                env.STAGING_PORT = sh(returnStdout: true, script: "kubectl -n ioc --kubeconfig=k8s-config get svc | grep '${service}' | awk '{print \$5}' | grep -o '[[:digit:]]*' | tail -n 1").trim()
                echo "port: $env.STAGING_PORT"
                env.STAGING_IP = sh(returnStdout: true, script: "kubectl -n ioc --kubeconfig=k8s-config get node -o wide | head -2 | tail -1 | awk '{print \$6'}").trim()
                echo "ip: $env.STAGING_IP"
            } else {
                error "Deploy service ${service} version ${version} to k8s ${enviroment} failure open port $env.STAGING_PORT"
            }
        }
    } catch (err) {
        error "Deploy to k8s failure"
    }
}

def getCurrentTestColor(service, namespace, enviroment) {
    def log = sh(script: 'cd cicd/' + enviroment + '; if kubectl get virtualservice ' + service + ' -n ' + namespace + ' --kubeconfig=k8s-config; then CURRENT_TEST_COLOR=$(kubectl get virtualservice ' + service + ' -n ' + namespace + ' --kubeconfig=k8s-config -o json | jq -r \'.spec.http[0].route[0].destination.subset\'); echo $CURRENT_TEST_COLOR; fi', returnStdout: true)
    echo "log: $log"
    def currentTestColor
    if (log.contains("blue")) {
        currentTestColor = "blue"
    } else if (log.contains("green")) {
        currentTestColor = "green"
    } else {
        echo "No Color Enviroment found, choose default enviroment: blue"
        currentTestColor = "blue"
    }
    echo "test version in enviroment: $currentTestColor "
    return currentTestColor;
}

def transferTraffic(enviroment, service, version) {
    echo "Transfer traffic from $env.ACTIVE_COLOR to $env.TEST_COLOR"
    try {
        def activeColor = env.TEST_COLOR
        def testColor = env.ACTIVE_COLOR
        sh """
            pwd
            cd cicd/${enviroment}
            ls -la
            sh update-route.sh  ${activeColor} ${testColor} ${service}
            kubectl -n ioc apply -f ${service}-route* --kubeconfig=k8s-config
            sleep 20
        """
        dir("${env.buildFolderResult}/cicd/${enviroment}") {
            echo "Get Pods, service detail"
            sh """
            	kubectl -n ioc get pods,svc --kubeconfig=k8s-config
            """
            def checkProcessRunning = sh(returnStdout: true, script: "kubectl -n ioc --kubeconfig=k8s-config get pods --sort-by=.status.startTime | grep '${service}' | tail -n 1 | awk '{print \$3}'").trim()
            echo "checkProcessRunning: $checkProcessRunning ${service}"
            if (checkProcessRunning == "Running") {
                env.STAGING_PORT = sh(returnStdout: true, script: "kubectl -n ioc --kubeconfig=k8s-config get svc | grep '${service}' | awk '{print \$5}' | grep -o '[[:digit:]]*' | tail -n 1").trim()
                echo "port: $env.STAGING_PORT"
                env.STAGING_IP = sh(returnStdout: true, script: "kubectl -n ioc --kubeconfig=k8s-config get node -o wide | head -2 | tail -1 | awk '{print \$6'}").trim()
                echo "ip: $env.STAGING_IP"
            } else {
                error "Deploy service ${service} version ${version} to k8s ${enviroment} failure open port $env.STAGING_PORT"
            }
        }
        env.ACTIVE_COLOR = activeColor
        env.TEST_COLOR = testColor
        echo "Transfer traffic success, ACTIVE_COLOR: $env.ACTIVE_COLOR, TEST_COLOR: $env.TEST_COLOR"
    } catch (err) {
        sh """
            pwd
            cd cicd/${enviroment}
            ls -la
            sh update-route.sh  ${env.ACTIVE_COLOR} ${env.TEST_COLOR} ${service}
            kubectl -n ioc apply -f ${service}-route* --kubeconfig=k8s-config
            sleep 20
        """
        error "transfer failure"
    }
}

def rollBackOrDeleteOldVersion(enviroment, service, version) {
    def deployInput = "none"
    try {
        deployer = env.project_maintainer_list
        echo "project_maintainer_list: ${env.project_maintainer_list}"

        timeout(time: 24, unit: 'HOURS') {
            deployInput = input(
                    submitter: "${deployer}",
                    submitterParameter: 'submitter',
                    message: 'Rollback or Delete old version', ok: "Process", parameters: [choice(choices: ['rollback', 'delete', 'none'], description: '', name: 'rollbackordelete')])
        }
    } catch (err) { // timeout reached or input false
        echo "Exception"
        def user = err.getCauses()[0].getUser()
        if ('SYSTEM' == user.toString()) { // SYSTEM means timeout.
            echo "Timeout is exceeded!"
        } else {
            echo "Aborted by: [${user}]"
        }
        deployInput['rollbackordelete'] = 'none'
    }
    env.rollbackordelete = deployInput['rollbackordelete']
    echo "Input value: $deployInput"

    if (env.rollbackordelete == "rollback") {
        echo "transfer traffic from $env.ACTIVE_COLOR to $env.TEST_COLOR"
        transferTraffic(enviroment, service, version)
    } else if (env.rollbackordelete == "delete") {
        echo "Delete old version in $env.TEST_COLOR enviroment"
        def folder = sh(script: 'pwd', returnStdout: true)
        env.buildFolderResult = folder.trim()
        def testColor = env.TEST_COLOR
        sh """
            pwd
            cd cicd/${enviroment}
            ls -la
			sh update-version.sh ${testColor} ${version} ${service}
            kubectl -n ioc delete -f ${service}-deployment* --kubeconfig=k8s-config
            sleep 20
        """
        echo "Delete Success"
    } else {
        echo "Do nothing"
    }
}

return [
        deployToPreRelease        : this.&deployToPreRelease,
        deployToPreRelease        : this.&deployToPreRelease,
        transferTraffic           : this.&transferTraffic,
        rollBackOrDeleteOldVersion: this.&rollBackOrDeleteOldVersion,
]
