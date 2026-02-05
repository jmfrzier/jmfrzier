pipelineJob('esp32-p4-brookesia-demo') {
  description('Build the ESP32-P4 Brookesia Phone demo with SonarQube analysis')
  definition {
    cps {
      script("""
pipeline {
  agent { label 'esp32-p4' }
  environment {
    IDF_TARGET     = 'esp32p4'
    IDF_PATH       = '/opt/esp/idf'
    PROJECT_PATH   = 'examples/esp32-p4-function-ev-board/examples/esp_brookesia_phone'
    SONARQUBE_URL  = 'http://sonarqube-sonarqube.sonarqube.svc.cluster.local:9000'
    SONAR_PROJECT  = 'esp32-p4-brookesia-demo'
  }
  stages {
    stage('Checkout') {
      steps {
        git url: 'https://github.com/espressif/esp-dev-kits.git', branch: 'master'
      }
    }

    stage('Setup') {
      steps {
        container('esp-idf') {
          withEnv(['IDF_PATH_FORCE=1']) {
            sh '''
              . \$IDF_PATH/export.sh
              idf.py --version
            '''
          }
        }
      }
    }

    stage('Configure') {
      steps {
        container('esp-idf') {
          dir("\${PROJECT_PATH}") {
            withEnv(['IDF_PATH_FORCE=1']) {
              sh '''
                . \$IDF_PATH/export.sh
                idf.py --preview set-target \$IDF_TARGET
              '''
            }
          }
        }
      }
    }

    stage('Build & Analyze') {
      steps {
        container('esp-idf') {
          dir("\${PROJECT_PATH}") {
            withEnv(['IDF_PATH_FORCE=1']) {
              sh '''
                . \$IDF_PATH/export.sh
            
                # 1. Build the project to generate compile_commands.json
                # ESP-IDF generates this in the 'build' directory by default
                idf.py --preview build
            
                # 2. Install cppcheck if it's missing (Debian/Ubuntu based containers)
                if ! command -v cppcheck &> /dev/null; then
                    apt-get update && apt-get install -y cppcheck
                fi

                # 3. Run Static Analysis
                # We use the build/compile_commands.json to tell cppcheck about your 
                # ESP32-P4 include paths and compiler defines.
                cppcheck --project=build/compile_commands.json \
                         --xml --xml-version=2 \
                         --enable=warning,style,performance,portability \
                         2> build/cppcheck-report.xml
              '''
            }
          }
        }
      }
    }

    stage('SonarQube Analysis') {
      steps {
        container('esp-idf') {
          dir("\${PROJECT_PATH}") {
            withCredentials([string(credentialsId: 'sonar-auth-token', variable: 'SONAR_TOKEN')]) {
              sh '''
                # Install sonar-scanner
                export SONAR_SCANNER_VERSION=5.0.1.3006
                curl -sSLo sonar-scanner.zip https://binaries.sonarsource.com/Distribution/sonar-scanner-cli/sonar-scanner-cli-\${SONAR_SCANNER_VERSION}-linux.zip
                unzip -q sonar-scanner.zip
                export PATH="\$PWD/sonar-scanner-\${SONAR_SCANNER_VERSION}-linux/bin:\$PATH"

                # Run SonarQube analysis
                sonar-scanner \\
                  -Dsonar.projectKey=\${SONAR_PROJECT} \\
                  -Dsonar.projectName="ESP32-P4 Brookesia Demo" \\
                  -Dsonar.sources=. \\
                  -Dsonar.cxx.file.suffixes=.cpp,.c,.h,.hpp \\
                  -Dsonar.cxx.cppcheck.reportPaths=build/cppcheck-report.xml \\
                  -Dsonar.cxx.cobertura.reportPaths=build/coverage.xml \\
                  -Dsonar.host.url=\${SONARQUBE_URL} \\
                  -Dsonar.token=\${SONAR_TOKEN} \\
                  -Dsonar.sourceEncoding=UTF-8 \\
                  -Dsonar.exclusions=build/**,managed_components/**
              '''
            }
          }
        }
      }
    }

    stage('Archive') {
      steps {
        dir("\${PROJECT_PATH}") {
          archiveArtifacts artifacts: 'build/*.bin, build/*.elf, build/*.map',
                           fingerprint: true,
                           allowEmptyArchive: true
        }
      }
    }
  }
  post {
    success {
      echo "ESP32-P4 Brookesia Phone demo build and analysis completed successfully"
    }
    failure {
      echo "Build or analysis failed — check logs"
    }
  }
}
      """)
      sandbox()
    }
  }
}
