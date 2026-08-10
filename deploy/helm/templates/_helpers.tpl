{{/*
NexusChain Umbrella Chart 模板辅助函数
============================================================================

约定：
  - 命名空间来自 .Values.global.namespace
  - 服务名标签键 app.kubernetes.io/name
  - part-of 标签 app.kubernetes.io/part-of=nexus
  - 所有子 chart 通过 import 这些 helper 保持命名一致
*/}}

{{/* Chart 全名：release-chartname，截断 63 字符，末尾去 - */}}
{{- define "nexus-chain.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/* Chart 名（短） */}}
{{- define "nexus-chain.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* 命名空间 */}}
{{- define "nexus-chain.namespace" -}}
{{- default "nexus" .Values.global.namespace -}}
{{- end -}}

{{/* Spring profile：优先 global.springProfile，回退 global.env */}}
{{- define "nexus-chain.springProfile" -}}
{{- if .Values.global.springProfile -}}
{{- .Values.global.springProfile -}}
{{- else -}}
{{- default "prod" .Values.global.env -}}
{{- end -}}
{{- end -}}

{{/* 通用标签 */}}
{{- define "nexus-chain.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
app.kubernetes.io/name: {{ include "nexus-chain.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: {{ default "nexus" .Values.global.partOfLabel }}
{{- end -}}

{{/* 通用 selector labels */}}
{{- define "nexus-chain.selectorLabels" -}}
app.kubernetes.io/name: {{ include "nexus-chain.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/* 镜像拉取 Secret 名列表（仅返回 name，供 deployment imagePullSecrets 引用） */}}
{{- define "nexus-chain.imagePullSecretNames" -}}
{{- range .Values.global.imagePullSecrets -}}
- name: {{ .name }}
{{- end -}}
{{- end -}}

{{/*
基础设施服务地址拼接：host:port
用法：{{ include "nexus-chain.nacosHttp" . }}
*/}}
{{- define "nexus-chain.nacosHttp" -}}
{{- printf "%s:%s" .Values.global.infrastructure.nacos.host (toString .Values.global.infrastructure.nacos.httpPort) -}}
{{- end -}}

{{- define "nexus-chain.nacosGrpc" -}}
{{- printf "%s:%s" .Values.global.infrastructure.nacos.host (toString .Values.global.infrastructure.nacos.grpcPort) -}}
{{- end -}}

{{- define "nexus-chain.seataTc" -}}
{{- printf "%s:%s" .Values.global.infrastructure.seata.host (toString .Values.global.infrastructure.seata.tcPort) -}}
{{- end -}}

{{- define "nexus-chain.zipkinEndpoint" -}}
{{- printf "http://%s:%s/api/v2/spans" .Values.global.infrastructure.zipkin.host (toString .Values.global.infrastructure.zipkin.port) -}}
{{- end -}}

{{- define "nexus-chain.postgresHost" -}}
{{- printf "%s:%s" .Values.global.infrastructure.postgres.host (toString .Values.global.infrastructure.postgres.port) -}}
{{- end -}}

{{- define "nexus-chain.redisHost" -}}
{{- printf "%s:%s" .Values.global.infrastructure.redis.host (toString .Values.global.infrastructure.redis.port) -}}
{{- end -}}