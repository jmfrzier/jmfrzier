pipelineJob('test-sonarqube-connectivity') {
    description('Test connectivity to SonarQube')
    definition {
        cps {
            script("""
pipeline {
  agent any
  environment {
    SONARQUBE_URL = 'http://sonarqube-sonarqube.sonarqube.svc.cluster.local:9000'
  }
  stages {
    stage('Test SonarQube Connection') {
      steps {
        script {
          withCredentials([string(credentialsId: 'sonar-auth-token', variable: 'SONAR_TOKEN')]) {
            sh "curl -s -u \$SONAR_TOKEN: \$SONARQUBE_URL/api/authentication/validate"
            sh "curl -s -u \$SONAR_TOKEN: \$SONARQUBE_URL/api/system/health"
            echo "Successfully connected to SonarQube"
          }
        }
      }
    }
  }
  post {
    success {
      echo 'SonarQube connectivity test PASSED'
    }
    failure {
      echo 'SonarQube connectivity test FAILED'
    }
  }
}
            """)
            sandbox()
        }
    }
}
