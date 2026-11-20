{{- define "nuna-audit-tables.fullname" -}}
{{- if contains .Chart.Name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "nuna-audit-tables.selectorLabels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "nuna-audit-tables.labels" -}}
{{ include "nuna-audit-tables.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{- define "nuna-audit-tables.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "nuna-audit-tables.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/* The Secret mounted as the config tree: the user's own, or the chart's when it has values; none otherwise. */}}
{{- define "nuna-audit-tables.secretName" -}}
{{- if .Values.secrets.existingSecret -}}
{{- .Values.secrets.existingSecret -}}
{{- else if .Values.secrets.values -}}
{{- include "nuna-audit-tables.fullname" . -}}
{{- end -}}
{{- end -}}
