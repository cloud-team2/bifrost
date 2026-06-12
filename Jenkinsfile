// Bifrost CI 파이프라인 (#123)
// 흐름: main 머지 → 변경된 서비스만 Kaniko 빌드 → Harbor push(:git-sha) → 그 서비스의 gitops image tag commit → ArgoCD(polling)가 sync.
//  - develop/PR: Test 단계만 (배포 트리거 아님)
//  - main:       변경 감지 → 바뀐 서비스만 Build & Push + gitops tag 갱신
//  - 빌드 도구: Kaniko (docker daemon 미사용, Dockerfile 실행). Harbor는 HTTP(in-cluster)라 --insecure.
//  - CD: ArgoCD가 gitops 브랜치 polling(reconcile) — webhook 미사용
//  - 라이브 설정 필요: harbor-push-secret(jenkins ns), github-pat(gitops push)
pipeline {
  agent {
    kubernetes {
      defaultContainer 'git'                     // Test/Detect/gitops = git 컨테이너(sh+git)
      yaml '''
apiVersion: v1
kind: Pod
spec:
  containers:
    # 서비스마다 별도 kaniko 컨테이너 — 한 컨테이너 재사용 시 kaniko가 빌드 중
    # base rootfs를 / 에 unpack하며 /busybox(셸)을 덮어 다음 빌드 sh가 죽는다.
    # 컨테이너를 분리하면 파일시스템이 독립이라 안전. (SERVICES와 1:1, 추가 시 함께 추가)
    - name: kaniko-ai-service
      image: gcr.io/kaniko-project/executor:v1.23.2-debug
      command: ["/busybox/cat"]
      tty: true
      volumeMounts:
        - name: harbor-auth
          mountPath: /kaniko/.docker
    - name: kaniko-operations-backend
      image: gcr.io/kaniko-project/executor:v1.23.2-debug
      command: ["/busybox/cat"]
      tty: true
      volumeMounts:
        - name: harbor-auth
          mountPath: /kaniko/.docker
    - name: kaniko-frontend
      image: gcr.io/kaniko-project/executor:v1.23.2-debug
      command: ["/busybox/cat"]
      tty: true
      volumeMounts:
        - name: harbor-auth
          mountPath: /kaniko/.docker
    # Kafka Connect 커스텀 이미지(#425) — Strimzi spec.image 용. 컨텍스트=레포 루트(멀티모듈 Gradle).
    - name: kaniko-kafka-connect
      image: gcr.io/kaniko-project/executor:v1.23.2-debug
      command: ["/busybox/cat"]
      tty: true
      volumeMounts:
        - name: harbor-auth
          mountPath: /kaniko/.docker
    - name: git
      image: alpine/git:2.45.2
      command: ["cat"]
      tty: true
  volumes:
    - name: harbor-auth
      secret:
        secretName: harbor-push-secret           # jenkins ns에 복제 필요
        items:
          - key: .dockerconfigjson
            path: config.json
'''
    }
  }

  environment {
    HARBOR      = 'harbor.harbor.svc.cluster.local'
    PROJECT     = 'library'
    TAG         = "${(env.GIT_COMMIT ?: 'latest').take(8)}"
    GITOPS_REPO = 'github.com/cloud-team2/bifrost.git'
    SERVICES    = 'ai-service operations-backend frontend'   // services/<svc> = 빌드 컨텍스트
    // Kafka Connect 커스텀 이미지(#425)가 push하는 고정 버전 태그. KafkaConnect spec.image가
    // 참조하는 값(infra/k8s/kafka/kafka-connect.yaml)과 일치해야 한다 — 이미지가 바뀌면 둘을 함께 bump.
    CONNECT_TAG = '1.0.0-converter'
    // (#632) frontend Grafana trace 딥링크 URL({traceId} 치환). 비우면 tracing 탭 딥링크 버튼 숨김.
    //   실제 Grafana/Tempo Explore URL로 채울 것. 예: https://grafana.<cluster>/explore?...{traceId}...
    GRAFANA_TRACE_URL = ''
  }

  options {
    timestamps()                                      // timestamper 플러그인 설치됨 (#161, jenkins-values)
    disableConcurrentBuilds()
    buildDiscarder(logRotator(numToKeepStr: '20'))
  }

  stages {
    // 모든 브랜치/PR: 검증 (배포 없음)
    stage('Test') {
      steps {
        echo "branch=${env.BRANCH_NAME} commit=${TAG} — lint/unit test (서비스별 후속 보강)"
      }
    }

    // 이 job은 SCM이 */main 단일 브랜치라 항상 main 빌드 = CD 수행.
    // (멀티브랜치가 아니라 BRANCH_NAME이 비므로 when{branch} 대신 job의 브랜치 설정으로 main 한정)
    stage('CD') {
      stages {
        stage('Detect changes') {
          steps {
            script {
              // 직전 성공 빌드 커밋 대비 변경 파일 (없으면 전체 빌드)
              def base = env.GIT_PREVIOUS_SUCCESSFUL_COMMIT
              def changed
              if (base) {
                sh "git config --global --add safe.directory ${WORKSPACE}"   // git 컨테이너(root)↔워크스페이스 소유자 불일치 회피
                changed = sh(returnStdout: true, script: "git diff --name-only ${base} ${GIT_COMMIT}").trim().split('\n') as List
              } else {
                echo '직전 성공 빌드 없음 → 전체 빌드'
                changed = SERVICES.split(' ').collect { "services/${it}/" } as List
              }
              env.TO_BUILD = SERVICES.split(' ').findAll { svc ->
                changed.any { it.startsWith("services/${svc}/") }
              }.join(' ')
              echo "변경된 서비스: '${env.TO_BUILD}'"

              // Kafka Connect 커스텀 이미지(#425): Dockerfile 또는 컨버터 모듈 변경 시 재빌드.
              // base 없으면(전체 빌드) 함께 빌드. (services/<svc> 모델과 별개라 따로 감지)
              env.BUILD_CONNECT = (!base || changed.any {
                it.startsWith('infra/docker/kafka-connect/') || it.startsWith('connect-plugins/')
              }) ? 'true' : ''
              echo "Kafka Connect 이미지 빌드: '${env.BUILD_CONNECT}'"
            }
          }
        }

        stage('Build & Push') {
          when { expression { env.TO_BUILD?.trim() } }
          steps {
            script {
              // 빌드 컨텍스트는 서비스마다 다르다:
              //  - operations-backend: 멀티모듈 Gradle → 컨텍스트=레포 루트(gradlew/settings.gradle/gradle 필요)
              //  - ai-service/frontend: self-contained Dockerfile → 컨텍스트=서비스 디렉토리
              // 각 서비스는 자기 전용 kaniko 컨테이너(kaniko-<svc>)에서 빌드 — 컨테이너 재사용 시
              // kaniko가 rootfs를 덮어 다음 빌드 셸이 사라지는 문제를 컨테이너 격리로 회피.
              for (svc in env.TO_BUILD.trim().split(' ')) {
                def ctx = (svc == 'operations-backend') ? "${WORKSPACE}" : "${WORKSPACE}/services/${svc}"
                // (#632) frontend만 Grafana 딥링크 URL을 빌드타임 주입(VITE_*는 build 시 정적으로 박힘). 비면 생략(버튼 숨김).
                def buildArgs = (svc == 'frontend' && env.GRAFANA_TRACE_URL?.trim()) ? "--build-arg VITE_GRAFANA_TRACE_URL='${env.GRAFANA_TRACE_URL}'" : ''
                container("kaniko-${svc}") {
                  sh """
                    /kaniko/executor \
                      --context=dir://${ctx} \
                      --dockerfile=${WORKSPACE}/services/${svc}/Dockerfile \
                      ${buildArgs} \
                      --destination=${HARBOR}/${PROJECT}/bifrost-${svc}:${TAG} \
                      --destination=${HARBOR}/${PROJECT}/bifrost-${svc}:latest \
                      --insecure --skip-tls-verify --insecure-pull
                  """
                }
              }
            }
          }
        }

        // Kafka Connect 커스텀 이미지(#425). 컨텍스트=레포 루트(멀티모듈 Gradle로 컨버터 JAR 빌드).
        // Harbor에 push만 한다 — 인프라 이미지라 main 머지마다 자동 롤하지 않는다. 롤아웃은
        // KafkaConnect spec.image 태그를 새 :${TAG}로 올려 적용(수동/별도, 런북 참조).
        stage('Build Kafka Connect image') {
          when { expression { env.BUILD_CONNECT?.trim() } }
          steps {
            container('kaniko-kafka-connect') {
              // spec.image가 참조하는 고정 태그(CONNECT_TAG) + 추적용 git-sha + latest로 push.
              // CONNECT_TAG가 매니페스트와 일치하므로 빌드 후 kafka-connect.yaml을 그대로 apply하면 된다.
              sh """
                /kaniko/executor \
                  --context=dir://${WORKSPACE} \
                  --dockerfile=${WORKSPACE}/infra/docker/kafka-connect/Dockerfile \
                  --destination=${HARBOR}/${PROJECT}/bifrost-kafka-connect:${CONNECT_TAG} \
                  --destination=${HARBOR}/${PROJECT}/bifrost-kafka-connect:${TAG} \
                  --destination=${HARBOR}/${PROJECT}/bifrost-kafka-connect:latest \
                  --insecure --skip-tls-verify --insecure-pull
              """
            }
          }
        }

        // 빌드된 서비스의 gitops 차트 image.tag만 갱신 → ArgoCD가 해당 앱만 sync
        stage('Bump gitops tag') {
          when { expression { env.TO_BUILD?.trim() } }
          steps {
            container('git') {
              withCredentials([usernamePassword(credentialsId: 'github-pat', usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
                sh '''
                  set -e
                  rm -rf gitops-work
                  git clone --branch gitops --depth 1 https://${GH_USER}:${GH_TOKEN}@${GITOPS_REPO} gitops-work
                  cd gitops-work
                  git config user.email "ci@bifrost"
                  git config user.name  "bifrost-ci"
                  for svc in ${TO_BUILD}; do
                    f="charts/${svc}/values.yaml"
                    [ -f "$f" ] && sed -i -E "s|^([[:space:]]*tag:).*|\\1 \\"${TAG}\\"|" "$f"
                  done
                  git add -A
                  git commit -m "ci: deploy ${TO_BUILD} @ ${TAG}" || { echo "변경 없음"; exit 0; }
                  git push origin gitops
                '''
              }
            }
          }
        }
      }
    }
  }

  post {
    failure { echo "BUILD FAILED: ${env.JOB_NAME} #${env.BUILD_NUMBER} (${TAG})" }   // TODO(라이브): Slack/email
    success { echo "OK: ${env.JOB_NAME} #${env.BUILD_NUMBER} (${TAG})" }
  }
}
