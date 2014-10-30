def qaCatalinaBase = '/opt/apache-tomcat-8-qa'
def qaHttpPort = 8081

def stagingCatalinaBase = '/opt/apache-tomcat-8-staging'
def stagingHttpPort = 8082

def perfsCatalinaBase = '/opt/apache-tomcat-8-perfs'
def perfsHttpPort = 8084

def productionCatalinaBase = '/opt/apache-tomcat-8-production'
def productionHttpPort = 8083


stage 'DEV'
node('linux') {
    // COMPILE AND JUNIT
    def src = 'https://github.com/cyrille-leclerc/spring-petclinic.git'
    // def src = '/Users/cleclerc/git/cyrille-leclerc/spring-petclinic'
    git url: src

    ensureMaven()
    sh 'mvn -o clean package'
    archive 'src/, pom.xml, target/petclinic.war'
    step $class: 'hudson.tasks.junit.JUnitResultArchiver', testResults: 'target/surefire-reports/*.xml'
}

parallel(qualityAnalysis: {
    // RUN SONAR ANALYSIS
    node('linux') {
        stage name: 'QUALITY_ANALYSIS', concurrency: 1

        unarchive mapping: ['src/': '.', 'pom.xml': '.']

        ensureMaven()
        sh 'mvn -o sonar:sonar'
    }
}, performanceTest: {
    // DEPLOY ON PERFS AND RUN JMETER STRESS TEST
    node('linux') {
        stage name: 'PERFS', concurrency: 1

        sh 'rm -rf *'
        unarchive mapping: ['src/': '.', 'pom.xml': '.', 'target/petclinic.war': 'petclinic.war']

        deployApp 'petclinic.war', perfsCatalinaBase, perfsHttpPort

        ensureMaven()
        sh 'mvn -o jmeter:jmeter'
    }
})

// DEPLOY ON THE QA SERVER
node('linux') {
    stage name: 'QA', concurrency: 1
    sh 'rm -rf *'
    unarchive mapping: ['target/petclinic.war': 'petclinic.war']

    deployApp 'petclinic.war', qaCatalinaBase, qaHttpPort
}

input message: "Does staging app http://localhost:$qaHttpPort/ look good? If yes, we deploy on staging.", ok: "DEPLOY TO STAGING!"

stage name: 'STAGING', concurrency: 1

node('linux') {
    // DEPLOY ON STAGING
    unarchive mapping: ['target/petclinic.war': 'petclinic.war']
    deployApp 'petclinic.war', stagingCatalinaBase, stagingHttpPort
    echo "Application is available on STAGING at http://localhost:$stagingHttpPort/"
}

// FUNCTIONS

/**
 * Deploy the app to the local Tomcat server identified by the given "catalinaBase"
 *
 * @param war path to the war file to deploy
 * @param catalinaBase path to the catalina base
 * @param httpPort listen port of the tomcat server
 */
def deployApp(war, catalinaBase, httpPort) {
    sh "${catalinaBase}/bin/shutdown.sh || :" // use "|| :" to ignore exception if server is not started
    sh "rm -rf ${catalinaBase}/webapps/ROOT"
    sh "rm -rf ${catalinaBase}/webapps/ROOT.war"
    sh "cp -rf ${war} ${catalinaBase}/webapps/ROOT.war"
    sh "${catalinaBase}/bin/startup.sh"
    echo "$catalinaBase server restarted with new webapp $war, see http://localhost:$httpPort"
    retry(count: 5) {
        sh "sleep 5 && curl http://localhost:$httpPort/health-check.jsp"
    }
}

/*
 * Deploy Maven on the slave if needed and add it to the path
 */
def ensureMaven() {
    env.PATH = "${tool 'Maven 3.x'}/bin:${env.PATH}"
}

