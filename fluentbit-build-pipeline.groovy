pipelineJob('fluentbit-multi-arch-build-scratch') {
  description('Build Fluent Bit for amd64 and arm64 as minimal scratch OCI images, push to Harbor with SonarQube analysis')

  definition {
    cps {
      script('''
        pipeline {
          agent { label 'c-builder' }

          environment {
            FLUENTBIT_VERSION = 'v5.0.2'
            HARBOR_URL        = 'http://192.168.0.167'
            HARBOR_REGISTRY   = '192.168.0.167'
            HARBOR_PROJECT    = 'fluentbit'
            IMAGE_NAME        = 'fluent-bit'
            SONARQUBE_URL     = 'http://sonarqube-sonarqube.sonarqube.svc.cluster.local:9000'
            SONAR_PROJECT     = 'fluentbit'
          }

          stages {

            stage('Checkout') {
              steps {
                container('gcc') {
                  sh 'rm -rf fluent-bit-src && git clone --depth 1 --branch ${FLUENTBIT_VERSION} https://github.com/fluent/fluent-bit.git fluent-bit-src'
                }
              }
            }

            stage('Install Build Dependencies') {
              steps {
                container('gcc') {
                  sh """
                    apt-get update && apt-get install -y --no-install-recommends \\
                      cmake make flex bison libyaml-dev libssl-dev \\
                      gcc-aarch64-linux-gnu g++-aarch64-linux-gnu \\
                      cppcheck unzip curl buildah fuse-overlayfs
                  """
                }
              }
            }

            stage('Build amd64 (static)') {
              steps {
                container('gcc') {
                  sh """
                    mkdir -p fluent-bit-src/build-amd64 && cd fluent-bit-src/build-amd64
                    cmake .. \\
                      -DFLB_RELEASE=On \\
                      -DFLB_TRACE=Off \\
                      -DFLB_JEMALLOC=Off \\
                      -DFLB_TLS=Off \\
                      -DFLB_SHARED_LIB=Off \\
                      -DFLB_STATIC_BUILD=On \\
                      -DFLB_EXAMPLES=Off \\
                      -DFLB_HTTP_SERVER=On \\
                      -DFLB_OUT_KAFKA=Off
                    make -j\$(nproc)
                    ls -la bin/fluent-bit
                  """
                }
              }
            }

            stage('Build arm64 (static cross-compile)') {
              steps {
                container('gcc') {
                  sh """
                    cat > /tmp/arm64-toolchain.cmake << 'TOOLCHAIN'
            set(CMAKE_SYSTEM_NAME Linux)
            set(CMAKE_SYSTEM_PROCESSOR aarch64)
            set(CMAKE_C_COMPILER aarch64-linux-gnu-gcc)
            set(CMAKE_CXX_COMPILER aarch64-linux-gnu-g++)
            set(CMAKE_FIND_ROOT_PATH /usr/aarch64-linux-gnu)
            set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
            set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
            set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
            TOOLCHAIN

                    mkdir -p fluent-bit-src/build-arm64 && cd fluent-bit-src/build-arm64
                    cmake .. \\
                      -DCMAKE_TOOLCHAIN_FILE=/tmp/arm64-toolchain.cmake \\
                      -DFLB_RELEASE=On \\
                      -DFLB_TRACE=Off \\
                      -DFLB_JEMALLOC=Off \\
                      -DFLB_TLS=Off \\
                      -DFLB_SHARED_LIB=Off \\
                      -DFLB_STATIC_BUILD=On \\
                      -DFLB_EXAMPLES=Off \\
                      -DFLB_HTTP_SERVER=On \\
                      -DFLB_OUT_KAFKA=Off
                    make -j\$(nproc)
                    ls -la bin/fluent-bit
                  """
                }
              }
            }

            stage('SonarQube Analysis') {
              steps {
                container('gcc') {
                  dir('fluent-bit-src') {
                    withCredentials([string(credentialsId: 'sonar-auth-token', variable: 'SONAR_TOKEN')]) {
                      sh """
                        if [ ! -f build-amd64/cppcheck-report.xml ]; then
                          cppcheck --project=build-amd64/compile_commands.json \\
                                   --xml --xml-version=2 \\
                                   --enable=warning,style,performance,portability \\
                                   2> build-amd64/cppcheck-report.xml
                        fi

                        SONAR_SCANNER_VERSION=5.0.1.3006
                        curl -sSLo sonar-scanner.zip https://binaries.sonarsource.com/Distribution/sonar-scanner-cli/sonar-scanner-cli-\${SONAR_SCANNER_VERSION}-linux.zip
                        unzip -q sonar-scanner.zip
                        export PATH="\$PWD/sonar-scanner-\${SONAR_SCANNER_VERSION}-linux/bin:\$PATH"

                        sonar-scanner \\
                          -Dsonar.projectKey=${SONAR_PROJECT} \\
                          -Dsonar.projectName="Fluent Bit" \\
                          -Dsonar.sources=. \\
                          -Dsonar.language=c++ \\
                          -Dsonar.cxx.file.suffixes=.c,.cpp,.h \\
                          -Dsonar.cxx.cppcheck.reportPaths=build-amd64/cppcheck-report.xml \\
                          -Dsonar.host.url=${SONARQUBE_URL} \\
                          -Dsonar.login=\${SONAR_TOKEN} \\
                          -Dsonar.sourceEncoding=UTF-8 \\
                          -Dsonar.exclusions=build-*/**,artifacts/**
                      """
                    }
                  }
                }
              }
            }

            stage('Archive Artifacts') {
              steps {
                sh """
                  mkdir -p artifacts
                  cp fluent-bit-src/build-amd64/bin/fluent-bit artifacts/fluent-bit-${FLUENTBIT_VERSION}-amd64
                  cp fluent-bit-src/build-arm64/bin/fluent-bit artifacts/fluent-bit-${FLUENTBIT_VERSION}-arm64
                """
                archiveArtifacts artifacts: 'artifacts/*', fingerprint: true
              }
            }

            stage('Build & Push Scratch Images') {
              steps {
                container('gcc') {
                  withCredentials([usernamePassword(credentialsId: 'harbor-robot-token', usernameVariable: 'HARBOR_USER', passwordVariable: 'HARBOR_PASS')]) {
                    sh """
                      for ARCH in amd64 arm64; do
                        BINARY=fluent-bit-src/build-\${ARCH}/bin/fluent-bit
                        FULL_IMAGE=${HARBOR_REGISTRY}/${HARBOR_PROJECT}/${IMAGE_NAME}:\${FLUENTBIT_VERSION}-\${ARCH}

                        ctr=\$(buildah from scratch)
                        mnt=\$(buildah mount \$ctr)
                        cp \$BINARY \$mnt/fluent-bit
                        buildah config --entrypoint '["/fluent-bit"]' \$ctr
                        buildah commit \$ctr \$FULL_IMAGE
                        buildah push --storage-driver vfs --tls-verify=false --creds "\${HARBOR_USER}:\${HARBOR_PASS}" \$FULL_IMAGE
                        buildah rm \$ctr
                      done
                      echo "[SUCCESS] Scratch images pushed for amd64 and arm64"
                    """
                  }
                }
              }
            }

          }

          post {
            success {
              echo "Fluent Bit ${FLUENTBIT_VERSION} multi-arch scratch build, SonarQube analysis, and push completed successfully"
            }
            failure {
              echo "Build or push failed - check logs"
            }
          }
        }
      ''')
      sandbox()
    }
  }
}
