def qaCatalinaBase = '/opt/apache-tomcat-8-qa'
def qaHttpPort = 8081

def stagingCatalinaBase = '/opt/apache-tomcat-8-staging'
def stagingHttpPort = 8082

def perfsCatalinaBase = '/opt/apache-tomcat-8-perfs'
def perfsHttpPort = 8084

def productionCatalinaBase = '/opt/apache-tomcat-8-production'
def productionHttpPort = 8083


stage 'Build'
node('linux') { // COMPILE AND JUNIT
    def src = 'https://github.com/cyrille-leclerc/spring-petclinic.git'
    // def src = '/Users/cleclerc/git/cyrille-leclerc/spring-petclinic'


    git url: 'https://github.com/cyrille-leclerc/spring-petclinic.git'

    ensureMaven()
    sh 'mvn -o clean package'
    sh 'tar -c -f src.tar src/ pom.xml'
    archive 'src.tar, target/petclinic.war'
    step $class: 'hudson.tasks.junit.JUnitResultArchiver', testResults: 'target/surefire-reports/*.xml'
}

stage name: 'Quality analysis and Perfs', concurrency: 1
parallel(qualityAnalysis: {

    node('linux') { // RUN SONAR ANALYSIS
        unarchive mapping: ['src.tar': '.']
        ensureMaven()
        sh 'tar -x -f src.tar'
        sh 'mvn -o sonar:sonar'
    }
}, performanceTest: {

    node('linux') { // DEPLOY ON PERFS AND RUN JMETER STRESS TEST

        sh 'rm -rf *'
        unarchive mapping: ['src.tar': '.', 'target/petclinic.war': 'petclinic.war']

        deployApp 'petclinic.war', perfsCatalinaBase, perfsHttpPort

        ensureMaven()
        sh 'tar -x -f src.tar'
        sh 'mvn -o jmeter:jmeter'

        shutdownApp(perfsCatalinaBase)
    }
})

stage name: 'QA', concurrency: 1
checkpoint 'ENTER QA'

node('linux') { // DEPLOY ON THE QA SERVER
    sh 'rm -rf *'
    unarchive mapping: ['target/petclinic.war': 'petclinic.war']

    deployApp 'petclinic.war', qaCatalinaBase, qaHttpPort
}


stage name: 'Staging', concurrency: 1
checkpoint 'CHOOSE TO ENTER STAGING'

input message: "Does QA app http://localhost:$qaHttpPort/ look good? If yes, we deploy on staging.", ok: "DEPLOY TO STAGING!"

node('linux') { // DEPLOY ON STAGING
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

/**
 * Shutdown the local Tomcat server identified by the given "catalinaBase"
 *
 * @param catalinaBase path to the catalina base
 */
def shutdownApp(catalinaBase) {
    sh "${catalinaBase}/bin/shutdown.sh || :" // use "|| :" to ignore exception if server is not started
    echo "$catalinaBase server is stopped"
}

/**
 * Deploy Maven on the slave if needed and add it to the path
 */
def ensureMaven() {
    env.PATH = "${tool 'Maven 3.x'}/bin:${env.PATH}"
}
