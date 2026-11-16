{{- define "keel-catalog.fullname" -}}
{{- if contains .Chart.Name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "keel-catalog.selectorLabels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "keel-catalog.labels" -}}
{{ include "keel-catalog.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{- define "keel-catalog.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "keel-catalog.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/* The Secret mounted as the config tree: the user's own, or the chart's when it has values; none otherwise. */}}
{{- define "keel-catalog.secretName" -}}
{{- if .Values.secrets.existingSecret -}}
{{- .Values.secrets.existingSecret -}}
{{- else if .Values.secrets.values -}}
{{- include "keel-catalog.fullname" . -}}
{{- end -}}
{{- end -}}

{{/*
The standard OTEL_* variables for the telemetry values (docs/observability/README.md). Spring Boot reads them; with no
endpoint nothing is exported.
*/}}
{{- define "keel-catalog.telemetryEnv" -}}
{{- $t := .Values.telemetry -}}
- name: KEEL_POD_NAME
  valueFrom:
    fieldRef:
      fieldPath: metadata.name
- name: KEEL_NODE_NAME
  valueFrom:
    fieldRef:
      fieldPath: spec.nodeName
- name: OTEL_SERVICE_NAME
  value: {{ .Chart.Name }}
{{- $attributes := list (printf "service.namespace=%s" .Release.Namespace) (printf "service.version=%s" (.Values.image.tag | default .Chart.AppVersion)) "service.instance.id=$(KEEL_POD_NAME)" (printf "k8s.namespace.name=%s" .Release.Namespace) "k8s.pod.name=$(KEEL_POD_NAME)" "k8s.node.name=$(KEEL_NODE_NAME)" }}
{{- range $name, $value := $t.resourceAttributes }}
{{- $attributes = append $attributes (printf "%s=%s" $name (toString $value)) }}
{{- end }}
- name: OTEL_RESOURCE_ATTRIBUTES
  value: {{ join "," $attributes | quote }}
- name: OTEL_TRACES_SAMPLER
  value: parentbased_traceidratio
- name: OTEL_TRACES_SAMPLER_ARG
  value: {{ $t.sampling | toString | quote }}
{{- if $t.endpoint }}
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: {{ $t.endpoint | quote }}
{{- if $t.headersSecret.name }}
- name: OTEL_EXPORTER_OTLP_HEADERS
  valueFrom:
    secretKeyRef:
      name: {{ $t.headersSecret.name }}
      key: {{ $t.headersSecret.key }}
{{- else if $t.headers }}
- name: OTEL_EXPORTER_OTLP_HEADERS
  value: {{ $t.headers | quote }}
{{- end }}
{{- if $t.caConfigMap.name }}
- name: OTEL_EXPORTER_OTLP_CERTIFICATE
  value: /etc/keel/otlp-ca/{{ $t.caConfigMap.key }}
{{- end }}
{{- end }}
{{- end }}
