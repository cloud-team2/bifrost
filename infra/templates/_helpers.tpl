{{- define "infra.albAnnotations" -}}
alb.ingress.kubernetes.io/load-balancer-name: {{ .Values.alb.loadBalancerName }}
alb.ingress.kubernetes.io/group.name: {{ .Values.alb.groupName }}
alb.ingress.kubernetes.io/scheme: {{ .Values.alb.scheme }}
alb.ingress.kubernetes.io/target-type: {{ .Values.alb.targetType }}
alb.ingress.kubernetes.io/subnets: {{ .Values.alb.subnets }}
alb.ingress.kubernetes.io/backend-protocol: HTTP
alb.ingress.kubernetes.io/success-codes: "200-399"
{{- end }}
