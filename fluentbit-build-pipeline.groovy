pipelineJob('fluentbit-multi-arch-build') {
  description('Build Fluent Bit from source for amd64 and arm64, push container images to Harbor')
  definition {
    cps {
      script("""
pipeline {
  agent { label 'c-builder' }
  environment {
    FLUENTBIT_VERSION = 'v5.0.2'
    HARBOR_URL        = 'http://192.168.0.176'
    HARBOR_REGISTRY   = '192.168.0.176'
    HARBOR_PROJECT    = 'fluentbit'
    IMAGE_NAME        = 'fluent-bit'
  }
  stages {
    stage('Checkout') {
      steps {
        container('gcc') {
          sh 'git clone --depth 1 --branch \${FLUENTBIT_VERSION} https://github.com/fluent/fluent-bit.git fluent-bit-src'
        }
      }
    }

    stage('Install Build Dependencies') {
      steps {
        container('gcc') {
          sh '''
            apt-get update && apt-get install -y --no-install-recommends \\
              cmake make flex bison \\
              libyaml-dev libssl-dev \\
              gcc-aarch64-linux-gnu g++-aarch64-linux-gnu \\
              buildah fuse-overlayfs \\
              curl jq
          '''
        }
      }
    }

    stage('Ensure Harbor Project Exists') {
      steps {
        container('gcc') {
          withCredentials([usernamePassword(credentialsId: 'harbor-robot-token', usernameVariable: 'HARBOR_USER', passwordVariable: 'HARBOR_PASS')]) {
            sh '''
              PROJECT_EXISTS=\$(curl -s -o /dev/null -w "%{http_code}" \\
                -u "\$HARBOR_USER:\$HARBOR_PASS" \\
                "\${HARBOR_URL}/api/v2.0/projects?name=\${HARBOR_PROJECT}")

              if [ "\$PROJECT_EXISTS" != "200" ]; then
                echo "[INFO] Creating Harbor project '\${HARBOR_PROJECT}'..."
                curl -s -u "\$HARBOR_USER:\$HARBOR_PASS" \\
                  -X POST "\${HARBOR_URL}/api/v2.0/projects" \\
                  -H "Content-Type: application/json" \\
                  -d "{\\"project_name\\": \\"\${HARBOR_PROJECT}\\", \\"public\\": true}"
              else
                echo "[INFO] Harbor project '\${HARBOR_PROJECT}' already exists."
              fi
            '''
          }
        }
      }
    }

    stage('Build amd64') {
      steps {
        container('gcc') {
          sh '''
            mkdir -p fluent-bit-src/build-amd64 && cd fluent-bit-src/build-amd64
            cmake .. \\
              -DFLB_RELEASE=On \\
              -DFLB_TRACE=Off \\
              -DFLB_JEMALLOC=On \\
              -DFLB_TLS=On \\
              -DFLB_SHARED_LIB=Off \\
              -DFLB_EXAMPLES=Off \\
              -DFLB_HTTP_SERVER=On \\
              -DFLB_OUT_KAFKA=Off
            make -j\$(nproc)
            echo "[INFO] amd64 build complete"
            ls -la bin/fluent-bit
          '''
        }
      }
    }

    stage('Build arm64 (cross-compile)') {
      steps {
        container('gcc') {
          sh '''
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
              -DFLB_TLS=On \\
              -DFLB_SHARED_LIB=Off \\
              -DFLB_EXAMPLES=Off \\
              -DFLB_HTTP_SERVER=On \\
              -DFLB_OUT_KAFKA=Off
            make -j\$(nproc)
            echo "[INFO] arm64 cross-compile build complete"
            ls -la bin/fluent-bit
          '''
        }
      }
    }

    stage('Archive Artifacts') {
      steps {
        sh '''
          mkdir -p artifacts
          cp fluent-bit-src/build-amd64/bin/fluent-bit artifacts/fluent-bit-\${FLUENTBIT_VERSION}-amd64
          cp fluent-bit-src/build-arm64/bin/fluent-bit artifacts/fluent-bit-\${FLUENTBIT_VERSION}-arm64
        '''
        archiveArtifacts artifacts: 'artifacts/*', fingerprint: true
      }
    }

    stage('Build & Push Container Images') {
      steps {
        container('gcc') {
          withCredentials([usernamePassword(credentialsId: 'harbor-robot-token', usernameVariable: 'HARBOR_USER', passwordVariable: 'HARBOR_PASS')]) {
            sh '''
              # Create Dockerfile for amd64
              cat > Dockerfile.amd64 << 'DOCKERFILE'
              FROM debian:bookworm-slim
              RUN apt-get update && apt-get install -y --no-install-recommends \\
                  libyaml-0-2 libssl3 ca-certificates && \\
                  rm -rf /var/lib/apt/lists/*
              COPY fluent-bit /usr/local/bin/fluent-bit
              EXPOSE 2020
              ENTRYPOINT ["/usr/local/bin/fluent-bit"]
              DOCKERFILE

              # Create Dockerfile for arm64
              cat > Dockerfile.arm64 << 'DOCKERFILE'
              FROM arm64v8/debian:bookworm-slim
              RUN apt-get update && apt-get install -y --no-install-recommends \\
                  libyaml-0-2 libssl3 ca-certificates && \\
                  rm -rf /var/lib/apt/lists/*
              COPY fluent-bit /usr/local/bin/fluent-bit
              EXPOSE 2020
              ENTRYPOINT ["/usr/local/bin/fluent-bit"]
              DOCKERFILE

              # Build amd64 image
              cp fluent-bit-src/build-amd64/bin/fluent-bit ./fluent-bit
              buildah bud --storage-driver vfs \\
                -f Dockerfile.amd64 \\
                -t \${HARBOR_REGISTRY}/\${HARBOR_PROJECT}/\${IMAGE_NAME}:\${FLUENTBIT_VERSION}-amd64 .
              rm -f ./fluent-bit

              # Build arm64 image
              cp fluent-bit-src/build-arm64/bin/fluent-bit ./fluent-bit
              buildah bud --storage-driver vfs \\
                -f Dockerfile.arm64 \\
                -t \${HARBOR_REGISTRY}/\${HARBOR_PROJECT}/\${IMAGE_NAME}:\${FLUENTBIT_VERSION}-arm64 .
              rm -f ./fluent-bit

              # Push to Harbor
              buildah push --storage-driver vfs \\
                --tls-verify=false \\
                --creds "\$HARBOR_USER:\$HARBOR_PASS" \\
                \${HARBOR_REGISTRY}/\${HARBOR_PROJECT}/\${IMAGE_NAME}:\${FLUENTBIT_VERSION}-amd64

              buildah push --storage-driver vfs \\
                --tls-verify=false \\
                --creds "\$HARBOR_USER:\$HARBOR_PASS" \\
                \${HARBOR_REGISTRY}/\${HARBOR_PROJECT}/\${IMAGE_NAME}:\${FLUENTBIT_VERSION}-arm64

              echo "[SUCCESS] Both images pushed to Harbor"
            '''
          }
        }
      }
    }
  }
  post {
    success {
      echo "Fluent Bit \${FLUENTBIT_VERSION} multi-arch build and push completed successfully"
    }
    failure {
      echo "Build or push failed — check logs"
    }
  }
}
      """)
      sandbox()
    }
  }
}
