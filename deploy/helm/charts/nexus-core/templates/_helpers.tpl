{{/* nexus-core helpers */}}
{{- define "nexus-core.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "nexus-core.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s" (include "nexus-core.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{/*
生成 BOOTSTRAPS 列表：nexus://nexus-core-N.nexus-core:9585（N = 0..replicaCount-1）。
与 deploy/k8s/20-core-statefulset.yml 的静态清单口径一致。
*/}}
{{- define "nexus-core.bootstraps" -}}
{{- $name := include "nexus-core.fullname" . -}}
{{- $port := 9585 -}}
{{- $items := list -}}
{{- range $i, $e := until (int .Values.replicaCount) -}}
{{- $items = append $items (printf "nexus://%s-%d.%s:%d" $name $i $name $port) -}}
{{- end -}}
{{- join "," $items -}}
{{- end -}}
