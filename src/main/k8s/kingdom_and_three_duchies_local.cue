// Copyright 2020 The Measurement System Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// cue cmd dump src/main/k8s/kingdom_and_three_duchies_from_cue.cue >
// src/main/k8s/kingdom_and_three_duchies_from_cue.yaml

package k8s

import (
	"encoding/yaml"
	"tool/cli"
)

command: dump: task: print: cli.Print & {
	text: """
          # Do NOT edit this file by hand.
          # This file is generated by kingdom_and_three_duchies.cue\n\n
          """ + yaml.MarshalStream(objects)
}

objects: [ for v in objectSets for x in v {x}]

objectSets: [
	fake_service,
	duchy_service,
	kingdom_service,
	fake_pod,
	duchy_pod,
	kingdom_pod,
	kingdom_job,
	setup_job,
]

fake_service: "spanner-emulator": {
	apiVersion: "v1"
	kind:       "Service"
	metadata: name: "spanner-emulator"
	spec: {
		selector: app: "spanner-emulator-app"
		type: "ClusterIP"
		ports: [{
			name:       "grpc"
			port:       9010
			protocol:   "TCP"
			targetPort: 9010
		}, {
			name:       "http"
			port:       9020
			protocol:   "TCP"
			targetPort: 9020
		}]
	}
}

fake_service: "fake-storage-server": #GrpcService & {
	_name:   "fake-storage-server"
	_system: "testing"
}

fake_pod: "spanner-emulator-pod": {
	apiVersion: "v1"
	kind:       "Pod"
	metadata: {
		name: "spanner-emulator-pod"
		labels: app: "spanner-emulator-app"
	}
	spec: containers: [{
		name:  "spanner-emulator-container"
		image: "gcr.io/cloud-spanner-emulator/emulator"
	}]
}

fake_pod: "fake-storage-server-pod": #ServerPod & {
	_name:   "fake-storage-server"
	_image:  "bazel/src/main/kotlin/org/wfanet/measurement/service/testing/storage:fake_storage_server_image"
	_system: "testing"
	_args: [
		"--debug-verbose-grpc-server-logging=true",
		"--port=8080",
	]
}

#Duchies: [
	{
		name: "a"
		key:  "057b22ef9c4e9626c22c13daed1363a1e6a5b309a930409f8d131f96ea2fa888"
	},
	{
		name: "b"
		key:  "31cc32e7cd53ff24f2b64ae8c531099af9867ebf5d9a659f742459947caa29b0"
	},
	{
		name: "c"
		key:  "338cce0306416b70e901436cb9eca5ac758e8ff41d7b58dabadf8726608ca6cc"
	},
]

#ComputationControlServiceFlags: [ for duchy_target in #Duchies {"--computation-control-service-target=duchy-\(duchy_target.name)=" +
	(#Target & {name: "\(duchy_target.name)-forwarding-storage-liquid-legions-server"}).target
}]

for duchy in #Duchies {

	duchy_service: {
		"\(duchy.name)-forwarding-storage-liquid-legions-server":          #GrpcService
		"\(duchy.name)-spanner-liquid-legions-computation-storage-server": #GrpcService
		"\(duchy.name)-spanner-forwarding-storage-server":                 #GrpcService
		"\(duchy.name)-publisher-data-server":                             #GrpcService
	}

	duchy_pod: {
		"\(duchy.name)-liquid-legions-herald-daemon-pod": #Pod & {
			_image: "bazel/src/main/kotlin/org/wfanet/measurement/duchy/herald:liquid_legions_herald_daemon_image"
			_args: [
				"--channel-shutdown-timeout=3s",
				"--computation-storage-service-target=" + (#Target & {name: "\(duchy.name)-spanner-liquid-legions-computation-storage-server"}).target,
				"--duchy-name=duchy-\(duchy.name)",
				"--duchy-public-keys-config=" + #DuchyPublicKeysConfig,
				"--global-computation-service-target=" + (#Target & {name: "global-computation-server"}).target,
				"--polling-interval=1m",
			]
		}
		"\(duchy.name)-forwarding-storage-liquid-legions-mill-daemon-pod": #Pod & {
			_image: "bazel/src/main/kotlin/org/wfanet/measurement/duchy/mill:forwarding_storage_liquid_legions_mill_daemon_image"
			_args:  [
				"--bytes-per-chunk=2000000",
				"--channel-shutdown-timeout=3s",
				"--computation-storage-service-target=" + (#Target & {name: "\(duchy.name)-spanner-liquid-legions-computation-storage-server"}).target,
				"--duchy-name=duchy-\(duchy.name)",
				"--duchy-public-keys-config=" + #DuchyPublicKeysConfig,
				"--duchy-secret-key=\(duchy.key)",
				"--global-computation-service-target=" + (#Target & {name: "global-computation-server"}).target,
				"--liquid-legions-decay-rate=23.0",
				"--liquid-legions-size=330000",
				"--metric-values-service-target=" + (#Target & {name: "\(duchy.name)-spanner-forwarding-storage-server"}).target,
				"--mill-id=duchy-\(duchy.name)-mill-1",
				"--polling-interval=1s",
				"--forwarding-storage-service-target=" + (#Target & {name: "fake-storage-server"}).target,
			] + #ComputationControlServiceFlags
		}
		"\(duchy.name)-forwarding-storage-liquid-legions-server-pod": #ServerPod & {
			_image: "bazel/src/main/kotlin/org/wfanet/measurement/service/internal/duchy/computation/control:forwarding_storage_liquid_legions_server_image"
			_args: [
				"--computation-storage-service-target=" + (#Target & {name: "\(duchy.name)-spanner-liquid-legions-computation-storage-server"}).target,
				"--debug-verbose-grpc-server-logging=true",
				"--duchy-name=duchy-\(duchy.name)",
				"--duchy-public-keys-config=" + #DuchyPublicKeysConfig,
				"--duchy-ids=duchy-\(#Duchies[0].name)",
				"--duchy-ids=duchy-\(#Duchies[1].name)",
				"--duchy-ids=duchy-\(#Duchies[2].name)",
				"--port=8080",
				"--forwarding-storage-service-target=" + (#Target & {name: "fake-storage-server"}).target,
			]
		}
		"\(duchy.name)-spanner-liquid-legions-computation-storage-server-pod": #ServerPod & {
			_image: "bazel/src/main/kotlin/org/wfanet/measurement/service/internal/duchy/computation/storage:spanner_liquid_legions_computation_storage_server_image"
			_args: [
				"--channel-shutdown-timeout=3s",
				"--debug-verbose-grpc-server-logging=true",
				"--duchy-name=duchy-\(duchy.name)",
				"--duchy-public-keys-config=" + #DuchyPublicKeysConfig,
				"--global-computation-service-target=" + (#Target & {name: "global-computation-server"}).target,
				"--port=8080",
				"--spanner-database=\(duchy.name)_duchy_computations",
				"--spanner-emulator-host=" + (#Target & {name: "spanner-emulator"}).target,
				"--spanner-instance=emulator-instance",
				"--spanner-project=ads-open-measurement",
			]
		}
		"\(duchy.name)-spanner-forwarding-storage-server-pod": #ServerPod & {
			_image: "bazel/src/main/kotlin/org/wfanet/measurement/service/internal/duchy/metricvalues:spanner_forwarding_storage_server_image"
			_args: [
				"--debug-verbose-grpc-server-logging=true",
				"--port=8080",
				"--spanner-database=\(duchy.name)_duchy_metric_values",
				"--spanner-emulator-host=" + (#Target & {name: "spanner-emulator"}).target,
				"--spanner-instance=emulator-instance",
				"--spanner-project=ads-open-measurement",
				"--forwarding-storage-service-target=" + (#Target & {name: "fake-storage-server"}).target,
			]
		}
		"\(duchy.name)-publisher-data-server-pod": #ServerPod & {
			_image: "bazel/src/main/kotlin/org/wfanet/measurement/service/v1alpha/publisherdata:publisher_data_server_image"
			_args: [
				"--debug-verbose-grpc-server-logging=true",
				"--duchy-name=duchy-\(duchy.name)",
				"--duchy-public-keys-config=" + #DuchyPublicKeysConfig,
				"--metric-values-service-target=" + (#Target & {name: "\(duchy.name)-spanner-forwarding-storage-server"}).target,
				"--port=8080",
				"--registration-service-target=127.0.0.1:9000",     // TODO: change once implemented.
				"--requisition-service-target=" + (#Target & {name: "requisition-server"}).target,
			]
		}
	}
	setup_job: "\(duchy.name)_push-spanner-schema-job": {
		apiVersion: "batch/v1"
		kind:       "Job"
		metadata: name: "\(duchy.name)-push-spanner-schema-job"
		spec: template: spec: {
			containers: [{
				name:            "push-spanner-schema-container"
				image:           "bazel/src/main/kotlin/org/wfanet/measurement/tools:push_spanner_schema_image"
				imagePullPolicy: "Never"
				args: [
					"--create-instance",
					"--databases=\(duchy.name)_duchy_computations=/app/wfa_measurement_system/src/main/db/gcp/computations.sdl",
					"--databases=\(duchy.name)_duchy_metric_values=/app/wfa_measurement_system/src/main/db/gcp/metric_values.sdl",
					"--emulator-host=" + (#Target & {name: "spanner-emulator"}).target,
					"--instance-config-id=spanner-emulator",
					"--instance-display-name=EmulatorInstance",
					"--instance-name=emulator-instance",
					"--instance-node-count=1",
					"--project-name=ads-open-measurement",
				]
			}]
			restartPolicy: "OnFailure"
		}
	}
}

kingdom_service: "gcp-kingdom-storage-server": #GrpcService
kingdom_service: "global-computation-server":  #GrpcService
kingdom_service: "requisition-server":         #GrpcService

kingdom_pod: "report-maker-daemon-pod": #Pod & {
	_image: "bazel/src/main/kotlin/org/wfanet/measurement/kingdom:report_maker_daemon_image"
	_args: [
		"--debug-verbose-grpc-client-logging=true",
		"--internal-services-target=" + (#Target & {name: "gcp-kingdom-storage-server"}).target,
		"--max-concurrency=32",
		"--throttler-overload-factor=1.2",
		"--throttler-poll-delay=1ms",
		"--throttler-time-horizon=2m",
	]
}

kingdom_pod: "report-starter-daemon-pod": #Pod & {
	_image: "bazel/src/main/kotlin/org/wfanet/measurement/kingdom:report_starter_daemon_image"
	_args: [
		"--debug-verbose-grpc-client-logging=true",
		"--internal-services-target=" + (#Target & {name: "gcp-kingdom-storage-server"}).target,
		"--max-concurrency=32",
		"--throttler-overload-factor=1.2",
		"--throttler-poll-delay=1ms",
		"--throttler-time-horizon=2m",
	]
}

kingdom_pod: "requisition-linker-daemon-pod": #Pod & {
	_image: "bazel/src/main/kotlin/org/wfanet/measurement/kingdom:requisition_linker_daemon_image"
	_args: [
		"--debug-verbose-grpc-client-logging=true",
		"--internal-services-target=" + (#Target & {name: "gcp-kingdom-storage-server"}).target,
		"--max-concurrency=32",
		"--throttler-overload-factor=1.2",
		"--throttler-poll-delay=1ms",
		"--throttler-time-horizon=2m",
	]
}

kingdom_pod: "gcp-kingdom-storage-server-pod": #ServerPod & {
	_image: "bazel/src/main/kotlin/org/wfanet/measurement/service/internal/kingdom:gcp_kingdom_storage_server_image"
	_args: [
		"--debug-verbose-grpc-server-logging=true",
		"--duchy-ids=duchy-\(#Duchies[0].name)",
		"--duchy-ids=duchy-\(#Duchies[1].name)",
		"--duchy-ids=duchy-\(#Duchies[2].name)",
		"--port=8080",
		"--spanner-database=kingdom",
		"--spanner-emulator-host=" + (#Target & {name: "spanner-emulator"}).target,
		"--spanner-instance=emulator-instance",
		"--spanner-project=ads-open-measurement",
	]
}

kingdom_pod: "global-computation-server-pod": #ServerPod & {
	_image: "bazel/src/main/kotlin/org/wfanet/measurement/service/v1alpha/globalcomputation:global_computation_server_image"
	_args: [
		"--debug-verbose-grpc-client-logging=true",
		"--debug-verbose-grpc-server-logging=true",
		"--duchy-ids=duchy-\(#Duchies[0].name)",
		"--duchy-ids=duchy-\(#Duchies[1].name)",
		"--duchy-ids=duchy-\(#Duchies[2].name)",
		"--internal-api-target=" + (#Target & {name: "gcp-kingdom-storage-server"}).target,
		"--port=8080",
	]
}

kingdom_pod: "requisition-server-pod": #ServerPod & {
	_image: "bazel/src/main/kotlin/org/wfanet/measurement/service/v1alpha/requisition:requisition_server_image"
	_args: [
		"--debug-verbose-grpc-client-logging=true",
		"--debug-verbose-grpc-server-logging=true",
		"--duchy-ids=duchy-\(#Duchies[0].name)",
		"--duchy-ids=duchy-\(#Duchies[1].name)",
		"--duchy-ids=duchy-\(#Duchies[2].name)",
		"--internal-api-target=" + (#Target & {name: "gcp-kingdom-storage-server"}).target,
		"--port=8080",
	]
}

kingdom_job: "kingdom-push-spanner-schema-job": {
	apiVersion: "batch/v1"
	kind:       "Job"
	metadata: name: "kingdom-push-spanner-schema-job"
	spec: template: spec: {
		containers: [{
			name:            "push-spanner-schema-container"
			image:           "bazel/src/main/kotlin/org/wfanet/measurement/tools:push_spanner_schema_image"
			imagePullPolicy: "Never"
			args: [
				"--create-instance",
				"--databases=kingdom=/app/wfa_measurement_system/src/main/db/gcp/kingdom.sdl",
				"--emulator-host=" + (#Target & {name: "spanner-emulator"}).target,
				"--instance-config-id=spanner-emulator",
				"--instance-display-name=EmulatorInstance",
				"--instance-name=emulator-instance",
				"--instance-node-count=1",
				"--project-name=ads-open-measurement",
			]
		}]
		restartPolicy: "OnFailure"
	}
}
