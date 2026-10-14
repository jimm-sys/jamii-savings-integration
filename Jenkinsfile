// Jenkinsfile - ONE pipeline for all three Jamii Savings integrations

def run(String cmd) { if (isUnix()) { sh cmd } else { bat cmd } }
def runStatus(String cmd) { return isUnix() ? sh(script: cmd, returnStatus: true) : bat(script: cmd, returnStatus: true) }
def winp(String p) { return isUnix() ? p : p.replace('/', '\\') }
def v(String name) { return isUnix() ? "\"\$${name}\"" : "\"%${name}%\"" }

def deployTo(String envName, String miHome, String miUrl, String apimUrl, String credId, boolean external, List apis, String product) {
    // ---- 1. Micro Integrator: copy the CAR into the target runtime ----
    def carDir = miHome + '/repository/deployment/server/carbonapps'
    echo "[${envName}] deploying CAR to ${carDir}"
    if (isUnix()) {
        sh "mkdir -p '${carDir}' && cp build/*.car '${carDir}/'"
    } else {
        def d = winp(carDir)
        bat "if not exist \"${d}\" mkdir \"${d}\"\r\ncopy /Y build\\*.car \"${d}\\\""
    }
    sleep time: 20, unit: 'SECONDS'   // MI hot-deploys the CAR

    // ---- 2. API Manager: import all APIs, all-or-nothing ----
    withCredentials([usernamePassword(credentialsId: credId, usernameVariable: 'APIM_USER', passwordVariable: 'APIM_PASS')]) {
        try {
            runStatus("apictl add env ${envName} --apim ${apimUrl}")   // ignore "already exists"
            run("apictl login ${envName} -u ${v('APIM_USER')} -p ${v('APIM_PASS')} -k")

            // phase 1: validate + stage every API WITHOUT deploying to the gateway
            def failed = []
            apis.each { a ->
                def rc = runStatus("apictl import api -f ${winp('apim/projects/' + a + '-1.0.0')} -e ${envName} --update --skip-deployments -k")
                if (rc != 0) { failed << a }
            }
            if (failed) { error("[${envName}] pre-flight import failed for: ${failed.join(', ')}. Nothing was deployed to the gateway.") }

            // phase 2: deploy
            apis.each { a ->
                def rc = runStatus("apictl import api -f ${winp('apim/projects/' + a + '-1.0.0')} -e ${envName} --update -k")
                if (rc != 0) { failed << a }
            }
            if (failed) { error("[${envName}] deployment FAILED for: ${failed.join(', ')}. Do not promote this build.") }

            def prc = runStatus("apictl import api-product -f ${winp('apim/products/' + product + '-1.0.0')} -e ${envName} -k --update-api-product")
            if (prc != 0) { error("[${envName}] API product import failed.") }
        } finally {
            runStatus("apictl logout ${envName}")   // apictl stores credentials in plain text; remove them
        }
    }

    // ---- 3. Smoke test the deployed APIs ----
    run("java scripts/SmokeTest.java ${miUrl}" + (external ? ' --external' : ''))
}

pipeline {
    agent any
    options { timestamps(); disableConcurrentBuilds(); timeout(time: 30, unit: 'MINUTES') }

    parameters {
        string(name: 'MI_HOME_DEV',   defaultValue: 'C:\\wso2\\wso2mi-4.6.0', description: 'DEV Micro Integrator home')
        string(name: 'MI_URL_DEV',    defaultValue: 'http://localhost:8290',   description: 'DEV Micro Integrator HTTP URL')
        string(name: 'APIM_URL_DEV',  defaultValue: 'https://localhost:9443',  description: 'DEV API Manager URL')
        booleanParam(name: 'RUN_EXTERNAL_TESTS', defaultValue: true, description: 'Also run smoke tests that need the internet')
        booleanParam(name: 'DEPLOY_PROD', defaultValue: false, description: 'Promote to PROD (asks for approval)')
        string(name: 'MI_HOME_PROD',  defaultValue: '', description: 'PROD Micro Integrator home')
        string(name: 'MI_URL_PROD',   defaultValue: '', description: 'PROD Micro Integrator HTTP URL')
        string(name: 'APIM_URL_PROD', defaultValue: '', description: 'PROD API Manager URL')
    }

    stages {
        stage('Checkout') {
            steps { checkout scm }
        }
        stage('Static tests') {
            steps { script { run('java scripts/ValidateArtifacts.java mi/artifacts') } }
        }
        stage('Build MI package') {
            steps { script { run('java scripts/BuildCar.java mi/artifacts build') } }
        }
        stage('Package APIM artifacts') {
            steps {
                script {
                    ['JamiiAccountsAPI', 'JamiiCustomersAPI', 'JamiiLoansAPI'].each { a ->
                        if (!fileExists("apim/projects/${a}-1.0.0/api.yaml")) { error("Missing APIM project for ${a}") }
                    }
                    if (!fileExists('apim/products/JamiiBankingProduct-1.0.0')) { error('Missing API product project') }
                }
                archiveArtifacts artifacts: 'build/*.car, apim/**', fingerprint: true
            }
        }
        stage('Deploy DEV') {
            steps {
                script {
                    deployTo('dev', params.MI_HOME_DEV, params.MI_URL_DEV, params.APIM_URL_DEV, 'apim-dev-admin',
                             params.RUN_EXTERNAL_TESTS, ['JamiiAccountsAPI', 'JamiiCustomersAPI', 'JamiiLoansAPI'], 'JamiiBankingProduct')
                }
            }
        }
        stage('Approve PROD') {
            when { expression { params.DEPLOY_PROD } }
            steps { input message: 'Deploy this build to PROD?', ok: 'Deploy' }
        }
        stage('Deploy PROD') {
            when { expression { params.DEPLOY_PROD } }
            steps {
                script {
                    deployTo('prod', params.MI_HOME_PROD, params.MI_URL_PROD, params.APIM_URL_PROD, 'apim-prod-admin',
                             false, ['JamiiAccountsAPI', 'JamiiCustomersAPI', 'JamiiLoansAPI'], 'JamiiBankingProduct')
                }
            }
        }
    }

    post {
        success { echo 'Pipeline succeeded: all three APIs deployed.' }
        failure { echo 'Pipeline FAILED: see the stage that turned red. A failed pre-flight means nothing reached the gateway.' }
    }
}
