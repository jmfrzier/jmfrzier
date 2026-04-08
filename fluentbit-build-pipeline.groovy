pipelineJob('fluentbit-build-pipeline') {
  description('Build Fluent Bit for amd64 and arm64 as minimal scratch OCI images with SonarQube analysis')
  definition {
    cps {
      script("""
        pipeline {
          agent { label 'c-builder' }
          environment {
            FLUENTBIT_VERSION = 'v5.0.2'
            HARBOR_REGISTRY = '192.168.0.167'
            HARBOR_PROJECT = 'fluentbit'
            IMAGE_NAME = 'fluent-bit'
            SONARQUBE_URL = 'http://sonarqube-sonarqube.sonarqube.svc.cluster.local:9000'
            SONAR_PROJECT = 'fluentbit'
          }
          stages {
            stage('Checkout') {
              steps {
                container('gcc') {
                  sh 'rm -rf fluent-bit-src && git clone --depth 1 --branch \${FLUENTBIT_VERSION} https://github.com/fluent/fluent-bit.git fluent-bit-src'
                }
              }
            }
            stage('Install Build Dependencies') {
              steps {
                container('gcc') {
                  sh '''
                    apt-get update && apt-get install -y --no-install-recommends \\
                      cmake make flex bison libyaml-dev libssl-dev \\
                      gcc-aarch64-linux-gnu g++-aarch64-linux-gnu \\
                      cppcheck unzip curl buildah fuse-overlayfs
                  '''
                }
              }
            }
            // ... keep the rest of your stages exactly the same ...
            stage('Build & Push Scratch Images') {
              steps {
                container('gcc') {
                  withCredentials([usernamePassword(credentialsId: 'harbor-robot-token', usernameVariable: 'HARBOR_USER', passwordVariable: 'HARBOR_PASS')]) {
                    sh '''
                      set -e
                      for ARCH in amd64 arm64; do
                        BINARY="fluent-bit-src/build-\${ARCH}/bin/fluent-bit"
                        FULL_IMAGE="\${HARBOR_REGISTRY}/\${HARBOR_PROJECT}/\${IMAGE_NAME}:\${FLUENTBIT_VERSION}-\${ARCH}"
                        ctr=\$(buildah from scratch)
                        mnt=\$(buildah mount \$ctr)
                        cp "\$BINARY" "\$mnt/fluent-bit"
                        buildah config --entrypoint '["/fluent-bit"]' \$ctr
                        buildah commit \$ctr "\$FULL_IMAGE"
                        buildah push --storage-driver vfs --tls-verify=false --creds "\${HARBOR_USER}:\${HARBOR_PASS}" "\$FULL_IMAGE"
                        buildah rm \$ctr
                      done
                      echo "[SUCCESS] Scratch images pushed for amd64 and arm64"
                    '''
                  }
                }
              }
            }
          } // stages
          post {
            success {
              echo "Fluent Bit \${FLUENTBIT_VERSION} multi-arch scratch build completed successfully"
            }
            failure {
              echo "Build or push failed - check logs"
            }
          }
        }
      """)
      sandbox(true)
    }
  }
}
