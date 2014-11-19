stage 'BUILD'
node('local-slave-1') {

    ws { // COMPILE AND JUNIT
        git url: 'https://github.com/cyrille-leclerc/spring-petclinic.git'
        env.PATH = "${tool 'Maven 3.x'}/bin:${env.PATH}"
        sh 'mvn -o -Dmaven.test.skip=true package'
        archive 'src, target/petclinic.war'
        step([$class: 'Fingerprinter', targets: 'target/petclinic.war'])
        // step $class: 'hudson.tasks.junit.JUnitResultArchiver', testResults: 'target/surefire-reports/*.xml'
    }

    ws { // DEPLOY WITH PUPPET
        stage 'DEPLOY'
        checkpoint 'Deploy'
        git url: 'https://github.com/cyrille-leclerc/vagrant-puppet-petclinic.git'
        sh "sed -i .bak 's/default.pp/default-jenkins.pp/g' Vagrantfile"
        sh "rm modules/petclinic/files/petclinic.war || :" // use "|| :" to ignore exception if the war is not there
        unarchive mapping: ['target/petclinic.war': 'modules/petclinic/files/petclinic.war']

        sh "vagrant up"
        sh "vagrant provision"
    }
}
