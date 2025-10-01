pipelineJob('esp32-p4-brookesia-demo') {
    description('Build the ESP32-P4 Brookesia Phone demo')
    definition {
        cps {
            script("""
pipeline {
  agent { label 'esp32-p4' }
  environment {
    IDF_TARGET = 'esp32p4'
    IDF_PATH = '/opt/esp/idf'
    PROJECT_PATH = 'examples/esp32-p4-function-ev-board/examples/esp_brookesia_phone'
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
          sh '. /opt/esp/idf/export.sh && idf.py --version'
        }
      }
    }
    stage('Configure') {
      steps {
        container('esp-idf') {
          dir("\${PROJECT_PATH}") {
            sh '. /opt/esp/idf/export.sh && idf.py --preview set-target \${IDF_TARGET}'
          }
        }
      }
    }
    stage('Build') {
      steps {
        container('esp-idf') {
          dir("\${PROJECT_PATH}") {
            sh '. /opt/esp/idf/export.sh && idf.py --preview build'
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
}
            """)
            sandbox()
        }
    }
}
