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
      defaultContainer 'kaniko'
      yaml '''
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: kaniko
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
  }

  options {
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
                changed = sh(returnStdout: true, script: "git diff --name-only ${base} ${GIT_COMMIT}").trim().split('\n') as List
              } else {
                echo '직전 성공 빌드 없음 → 전체 빌드'
                changed = SERVICES.split(' ').collect { "services/${it}/" } as List
              }
              env.TO_BUILD = SERVICES.split(' ').findAll { svc ->
                changed.any { it.startsWith("services/${svc}/") }
              }.join(' ')
              echo "변경된 서비스: '${env.TO_BUILD}'"
            }
          }
        }

        stage('Build & Push') {
          when { expression { env.TO_BUILD?.trim() } }
          steps {
            container('kaniko') {
              script {
                for (svc in env.TO_BUILD.trim().split(' ')) {
                  sh """
                    /kaniko/executor \
                      --context=dir://${WORKSPACE}/services/${svc} \
                      --dockerfile=${WORKSPACE}/services/${svc}/Dockerfile \
                      --destination=${HARBOR}/${PROJECT}/bifrost-${svc}:${TAG} \
                      --destination=${HARBOR}/${PROJECT}/bifrost-${svc}:latest \
                      --insecure --skip-tls-verify --insecure-pull
                  """
                }
              }
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
