{{- define "keel-maintenance.fullname" -}}
{{- if contains .Chart.Name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "keel-maintenance.selectorLabels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "keel-maintenance.labels" -}}
{{ include "keel-maintenance.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{- define "keel-maintenance.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "keel-maintenance.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/* The Secret mounted as the config tree: the user's own, or the chart's when it has values; none otherwise. */}}
{{- define "keel-maintenance.secretName" -}}
{{- if .Values.secrets.existingSecret -}}
{{- .Values.secrets.existingSecret -}}
{{- else if .Values.secrets.values -}}
{{- include "keel-maintenance.fullname" . -}}
{{- end -}}
{{- end -}}

{{/* Whether compaction runs: an image is named. The worker then calls the Kubernetes API. */}}
{{- define "keel-maintenance.compaction" -}}
{{- if dig "keel" "maintenance" "compaction" "image" "" .Values.config -}}true{{- end -}}
{{- end -}}
