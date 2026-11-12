{{/* Every object is named after the release, so the Service's DNS name is the release name. */}}
{{- define "fidice-catalog.labels" -}}
app.kubernetes.io/name: fidice-catalog
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
