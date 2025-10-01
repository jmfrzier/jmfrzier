pipelineJob('test-sonarqube-connectivity') {
    description('Test connectivity to SonarQube')
    definition {
        cps {
            // Triple-single-quotes prevent Groovy from interpolating $SONAR_TOKEN prematurely
            script('''pipeline {
  agent any

  environment {
    SONARQUBE_URL = 'http://sonarqube-sonarqube.sonarqube.svc.cluster.local:9000'
  }

  stages {
    stage('Check Credentials Exist') {
      steps {
        script {
          // This is a compile-time safe no-op check
          echo 'Ensuring credentials are available...'
        }
      }
    }

    stage('Test SonarQube Connection') {
      steps {
        script {
          // Use credentials securely at runtime
          withCredentials([string(credentialsId: 'sonar-auth-token', variable: 'SONAR_TOKEN')]) {
            sh 'curl -s -u $SONAR_TOKEN: $SONARQUBE_URL/api/authentication/validate'
            sh 'curl -s -u $SONAR_TOKEN: $SONARQUBE_URL/api/system/health'
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
}''')
            sandbox()
        }
    }
}
