pipelineJob('cjson-sonarqube-analysis') {
  description('Build cJSON with unit tests, code coverage, and SonarQube analysis via sonar-cxx plugin')
  definition {
    cps {
      script("""
pipeline {
  agent { label 'c-builder' }
  environment {
    SONARQUBE_URL  = 'http://sonarqube-sonarqube.sonarqube.svc.cluster.local:9000'
    SONAR_PROJECT  = 'cjson-analysis'
  }
  stages {
    stage('Checkout') {
      steps {
        container('gcc') {
          git url: 'https://github.com/DaveGamble/cJSON.git', branch: 'master'
        }
      }
    }

    stage('Install Tools') {
      steps {
        container('gcc') {
          sh '''
            apt-get update && apt-get install -y --no-install-recommends \\
              cmake make cppcheck python3-pip unzip curl default-jdk
            pip3 install gcovr --break-system-packages || pip3 install gcovr
          '''
        }
      }
    }

    stage('Build with Coverage') {
      steps {
        container('gcc') {
          sh '''
            mkdir -p build && cd build
            cmake .. -DENABLE_CJSON_TEST=ON \\
                     -DCMAKE_C_FLAGS="--coverage -fprofile-arcs -ftest-coverage" \\
                     -DCMAKE_BUILD_TYPE=Debug
            make -j\$(nproc)
          '''
        }
      }
    }

    stage('Run Tests') {
      steps {
        container('gcc') {
          sh '''
            cd build
            ctest --output-on-failure
          '''
        }
      }
    }

    stage('Generate Reports') {
      steps {
        container('gcc') {
          sh '''
            # Cppcheck static analysis
            cppcheck --xml --xml-version=2 \\
                     --enable=warning,style,performance,portability \\
                     --suppress=missingIncludeSystem \\
                     -I . \\
                     cJSON.c cJSON_Utils.c \\
                     2> build/cppcheck-report.xml

            # Code coverage (Cobertura XML)
            gcovr --root . \\
                  --filter 'cJSON\\.c' --filter 'cJSON_Utils\\.c' \\
                  --exclude 'build/' --exclude 'test/' --exclude 'fuzzing/' \\
                  --xml --output build/coverage.xml

            # Print summary to console
            gcovr --root . \\
                  --filter 'cJSON\\.c' --filter 'cJSON_Utils\\.c' \\
                  --exclude 'build/' --exclude 'test/' --exclude 'fuzzing/'
          '''
        }
      }
    }

    stage('SonarQube Analysis') {
      steps {
        container('gcc') {
          withCredentials([string(credentialsId: 'sonar-auth-token', variable: 'SONAR_TOKEN')]) {
            sh '''
              export SONAR_SCANNER_VERSION=5.0.1.3006
              curl -sSLo sonar-scanner.zip https://binaries.sonarsource.com/Distribution/sonar-scanner-cli/sonar-scanner-cli-\${SONAR_SCANNER_VERSION}-linux.zip
              unzip -q sonar-scanner.zip
              export PATH="\$PWD/sonar-scanner-\${SONAR_SCANNER_VERSION}-linux/bin:\$PATH"

              sonar-scanner \\
                -Dsonar.projectKey=\${SONAR_PROJECT} \\
                -Dsonar.projectName="cJSON Library" \\
                -Dsonar.sources=. \\
                -Dsonar.language=c++ \\
                -Dsonar.cxx.file.suffixes=.c,.h \\
                -Dsonar.cxx.cppcheck.reportPaths=build/cppcheck-report.xml \\
                -Dsonar.cxx.cobertura.reportPaths=build/coverage.xml \\
                -Dsonar.host.url=\${SONARQUBE_URL} \\
                -Dsonar.token=\${SONAR_TOKEN} \\
                -Dsonar.sourceEncoding=UTF-8 \\
                -Dsonar.exclusions=build/**,test/**,fuzzing/**
            '''
          }
        }
      }
    }
  }
  post {
    success {
      echo "cJSON build, test, coverage, and SonarQube analysis completed successfully"
    }
    failure {
      echo "Pipeline failed — check logs"
    }
  }
}
      """)
      sandbox()
    }
  }
}
