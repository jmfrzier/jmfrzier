pipelineJob('snes9x-esp32-p4') {
  description('Build the snes9x_esp32 SNES emulator for ESP32-P4-Function-EV-Board with SonarQube analysis (compile-only smoke test, no flashing)')
  definition {
    cps {
      script("""
pipeline {
  agent { label 'esp32-p4' }
  environment {
    IDF_TARGET     = 'esp32p4'
    IDF_PATH       = '/opt/esp/idf'
    SONARQUBE_URL  = 'http://sonarqube-sonarqube.sonarqube.svc.cluster.local:9000'
    SONAR_PROJECT  = 'snes9x-esp32-p4'
  }
  stages {
    stage('Checkout') {
      steps {
        git url: 'https://github.com/fcipaq/snes9x_esp32.git', branch: 'master'
      }
    }

    stage('Select hardware target') {
      steps {
        container('esp-idf') {
          sh '''
            # HW_CONFIG (0) = ESP32-P4-Function-EV-Board by Espressif, the board this
            # cluster already targets for the Brookesia demo. Repo defaults to (3),
            # a different prototype board (Pico Held 2 v1.4).
            sed -i 's/#define HW_CONFIG ([0-9])/#define HW_CONFIG (0)/' components/engine/hwcfg.h
            grep -n "#define HW_CONFIG" components/engine/hwcfg.h
          '''
        }
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
          withEnv(['IDF_PATH_FORCE=1']) {
            sh '''
              . \$IDF_PATH/export.sh
              idf.py --preview set-target \$IDF_TARGET
            '''
          }
        }
      }
    }

    stage('Build & Analyze') {
      steps {
        container('esp-idf') {
          withEnv(['IDF_PATH_FORCE=1']) {
            sh '''
              . \$IDF_PATH/export.sh

              # 1. Build to generate compile_commands.json
              idf.py --preview build

              # 2. Install cppcheck if missing
              if ! command -v cppcheck &> /dev/null; then
                  apt-get update && apt-get install -y cppcheck
              fi

              # 3. Static analysis using the build's compile database for accurate
              #    include/macro resolution
              cppcheck --project=build/compile_commands.json \
                       --xml --xml-version=2 \
                       --enable=warning,style,performance,portability \
                       2> build/cppcheck-report.xml
            '''
          }
        }
      }
    }

    stage('SonarQube Analysis') {
      steps {
        container('esp-idf') {
          withCredentials([string(credentialsId: 'sonar-auth-token', variable: 'SONAR_TOKEN')]) {
            sh '''
              export SONAR_SCANNER_VERSION=8.1.0.6389
              curl -sSLo sonar-scanner.zip https://binaries.sonarsource.com/Distribution/sonar-scanner-cli/sonar-scanner-cli-\${SONAR_SCANNER_VERSION}-linux-x64.zip
              unzip -q -o sonar-scanner.zip
              export PATH="\$PWD/sonar-scanner-\${SONAR_SCANNER_VERSION}-linux-x64/bin:\$PATH"

              sonar-scanner \\
                -Dsonar.projectKey=\${SONAR_PROJECT} \\
                -Dsonar.projectName="snes9x ESP32-P4" \\
                -Dsonar.sources=. \\
                -Dsonar.cxx.file.suffixes=.c,.cpp,.cc,.cxx,.h,.hpp,.hh \\
                -Dsonar.cxx.jsonCompilationDatabase=build/compile_commands.json \\
                -Dsonar.cxx.cppcheck.reportPaths=build/cppcheck-report.xml \\
                -Dsonar.host.url=\${SONARQUBE_URL} \\
                -Dsonar.token=\${SONAR_TOKEN} \\
                -Dsonar.sourceEncoding=UTF-8 \\
                -Dsonar.exclusions=build/**,managed_components/**,sonar-scanner-*/**,sonar-scanner.zip,**/*.png,**/*.jpg,**/assets/**
            '''
          }
        }
      }
    }

    stage('Archive') {
      steps {
        archiveArtifacts artifacts: 'build/*.bin, build/*.elf, build/*.map',
                         fingerprint: true,
                         allowEmptyArchive: true
      }
    }
  }
  post {
    success {
      echo "snes9x ESP32-P4 build and analysis completed successfully"
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
