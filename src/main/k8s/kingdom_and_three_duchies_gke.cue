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
	(#Target & {name: "\(duchy_target.name)-gcs-liquid-legions-server"}).target
}]

for duchy in #Duchies {

	duchy_service: {
		"\(duchy.name)-gcs-liquid-legions-server":                         #GrpcService
		"\(duchy.name)-spanner-liquid-legions-computation-storage-server": #GrpcService
		"\(duchy.name)-gcp-server":                                        #GrpcService
		"\(duchy.name)-publisher-data-server":                             #GrpcService
	}

	duchy_pod: {
		"\(duchy.name)-liquid-legions-herald-daemon-pod": #Pod & {
			_image: "gcr.io/ads-open-measurement/duchy/liquid-legions-v1-herald"
			_args: [
				"--channel-shutdown-timeout=3s",
				"--computation-storage-service-target=" + (#Target & {name: "\(duchy.name)-spanner-liquid-legions-computation-storage-server"}).target,
				"--duchy-name=duchy-\(duchy.name)",
				"--duchy-public-keys-config=" + #DuchyPublicKeysConfig,
				"--global-computation-service-target=" + (#Target & {name: "global-computation-server"}).target,
				"--polling-interval=1m",
			]
			_imagePullPolicy: "Always"
		}
		"\(duchy.name)-gcs-liquid-legions-mill-daemon-pod": #Pod & {
			_image: "gcr.io/ads-open-measurement/duchy/liquid-legions-v1-mill"
			_args:  [
				"--bytes-per-chunk=2000000",
				"--channel-shutdown-timeout=3s",
				"--computation-storage-service-target=" + (#Target & {name: "\(duchy.name)-spanner-liquid-legions-computation-storage-server"}).target,
				"--duchy-name=duchy-\(duchy.name)",
				"--duchy-public-keys-config=" + #DuchyPublicKeysConfig,
				"--duchy-secret-key=\(duchy.key)",
				"--global-computation-service-target=" + (#Target & {name: "global-computation-server"}).target,
				"--google-cloud-storage-bucket=",
				"--google-cloud-storage-project=",
				"--liquid-legions-decay-rate=23.0",
				"--liquid-legions-size=330000",
				"--metric-values-service-target=" + (#Target & {name: "\(duchy.name)-gcp-server"}).target,
				"--mill-id=duchy-\(duchy.name)-mill-1",
				"--polling-interval=1s",
			] + #ComputationControlServiceFlags
			_imagePullPolicy: "Always"
		}
		"\(duchy.name)-gcs-liquid-legions-server-pod": #ServerPod & {
			_image: "gcr.io/ads-open-measurement/duchy/liquid-legions-v1-computation-control"
			_args: [
				"--computation-storage-service-target=" + (#Target & {name: "\(duchy.name)-spanner-liquid-legions-computation-storage-server"}).target,
				"--debug-verbose-grpc-server-logging=true",
				"--duchy-name=duchy-\(duchy.name)",
				"--duchy-public-keys-config=" + #DuchyPublicKeysConfig,
				"--duchy-ids=duchy-\(#Duchies[0].name)",
				"--duchy-ids=duchy-\(#Duchies[1].name)",
				"--duchy-ids=duchy-\(#Duchies[2].name)",
				"--google-cloud-storage-bucket=",
				"--google-cloud-storage-project=",
				"--port=8080",
			]
			_imagePullPolicy: "Always"
		}
		"\(duchy.name)-spanner-liquid-legions-computation-storage-server-pod": #ServerPod & {
			_image: "gcr.io/ads-open-measurement/duchy/liquid-legions-v1-spanner-computation-storage"
			_args: [
				"--channel-shutdown-timeout=3s",
				"--debug-verbose-grpc-server-logging=true",
				"--duchy-name=duchy-\(duchy.name)",
				"--duchy-public-keys-config=" + #DuchyPublicKeysConfig,
				"--global-computation-service-target=" + (#Target & {name: "global-computation-server"}).target,
				"--port=8080",
				"--spanner-database=\(duchy.name)_duchy_computations",
				"--spanner-instance=qa-instance",
				"--spanner-project=ads-open-measurement",
			]
			_imagePullPolicy: "Always"
		}
		"\(duchy.name)-gcp-server-pod": #ServerPod & {
			_image: "gcr.io/ads-open-measurement/duchy/metric-values"
			_args: [
				"--debug-verbose-grpc-server-logging=true",
				"--google-cloud-storage-bucket=",
				"--google-cloud-storage-project=",
				"--port=8080",
				"--spanner-database=\(duchy.name)_duchy_metric_values",
				"--spanner-instance=qa-instance",
				"--spanner-project=ads-open-measurement",
			]
			_imagePullPolicy: "Always"
		}
		"\(duchy.name)-publisher-data-server-pod": #ServerPod & {
			_image: "gcr.io/ads-open-measurement/duchy/publisher-data"
			_args: [
				"--debug-verbose-grpc-server-logging=true",
				"--duchy-name=duchy-\(duchy.name)",
				"--duchy-public-keys-config=" + #DuchyPublicKeysConfig,
				"--metric-values-service-target=" + (#Target & {name: "\(duchy.name)-gcp-server"}).target,
				"--port=8080",
				"--registration-service-target=127.0.0.1:9000",     // TODO: change once implemented.
				"--requisition-service-target=" + (#Target & {name: "requisition-server"}).target,
			]
			_imagePullPolicy: "Always"
		}
	}
	setup_job: "\(duchy.name)_push-spanner-schema-job": {
		apiVersion: "batch/v1"
		kind:       "Job"
		metadata: name: "\(duchy.name)-push-spanner-schema-job"
		spec: template: spec: {
			containers: [{
				name:            "push-spanner-schema-container"
				image:           "gcr.io/ads-open-measurement/setup/push-spanner-schema"
				imagePullPolicy: "Always"
				args: [
					"--ignore-already-existing-databases",
					"--drop-databases-first",
					"--databases=\(duchy.name)_duchy_computations=/app/wfa_measurement_system/src/main/db/gcp/computations.sdl",
					"--databases=\(duchy.name)_duchy_metric_values=/app/wfa_measurement_system/src/main/db/gcp/metric_values.sdl",
					"--instance-name=qa-instance",
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
	_image: "gcr.io/ads-open-measurement/kingdom/report-maker"
	_args: [
		"--debug-verbose-grpc-client-logging=true",
		"--internal-services-target=" + (#Target & {name: "gcp-kingdom-storage-server"}).target,
		"--max-concurrency=32",
		"--throttler-overload-factor=1.2",
		"--throttler-poll-delay=1ms",
		"--throttler-time-horizon=2m",
	]
	_imagePullPolicy: "Always"
}

kingdom_pod: "report-starter-daemon-pod": #Pod & {
	_image: "gcr.io/ads-open-measurement/kingdom/report-starter"
	_args: [
		"--debug-verbose-grpc-client-logging=true",
		"--internal-services-target=" + (#Target & {name: "gcp-kingdom-storage-server"}).target,
		"--max-concurrency=32",
		"--throttler-overload-factor=1.2",
		"--throttler-poll-delay=1ms",
		"--throttler-time-horizon=2m",
	]
	_imagePullPolicy: "Always"
}

kingdom_pod: "requisition-linker-daemon-pod": #Pod & {
	_image: "gcr.io/ads-open-measurement/kingdom/requisition-linker"
	_args: [
		"--debug-verbose-grpc-client-logging=true",
		"--internal-services-target=" + (#Target & {name: "gcp-kingdom-storage-server"}).target,
		"--max-concurrency=32",
		"--throttler-overload-factor=1.2",
		"--throttler-poll-delay=1ms",
		"--throttler-time-horizon=2m",
	]
	_imagePullPolicy: "Always"
}

kingdom_pod: "gcp-kingdom-storage-server-pod": #ServerPod & {
	_image: "gcr.io/ads-open-measurement/kingdom/storage-server"
	_args: [
		"--debug-verbose-grpc-server-logging=true",
		"--duchy-ids=duchy-\(#Duchies[0].name)",
		"--duchy-ids=duchy-\(#Duchies[1].name)",
		"--duchy-ids=duchy-\(#Duchies[2].name)",
		"--port=8080",
		"--spanner-database=kingdom",
		"--spanner-instance=qa-instance",
		"--spanner-project=ads-open-measurement",
	]
	_imagePullPolicy: "Always"
}

kingdom_pod: "global-computation-server-pod": #ServerPod & {
	_image: "gcr.io/ads-open-measurement/kingdom/global-computation"
	_args: [
		"--debug-verbose-grpc-client-logging=true",
		"--debug-verbose-grpc-server-logging=true",
		"--duchy-ids=duchy-\(#Duchies[0].name)",
		"--duchy-ids=duchy-\(#Duchies[1].name)",
		"--duchy-ids=duchy-\(#Duchies[2].name)",
		"--internal-api-target=" + (#Target & {name: "gcp-kingdom-storage-server"}).target,
		"--port=8080",
	]
	_imagePullPolicy: "Always"
}

kingdom_pod: "requisition-server-pod": #ServerPod & {
	_image: "gcr.io/ads-open-measurement/kingdom/requisition"
	_args: [
		"--debug-verbose-grpc-client-logging=true",
		"--debug-verbose-grpc-server-logging=true",
		"--duchy-ids=duchy-\(#Duchies[0].name)",
		"--duchy-ids=duchy-\(#Duchies[1].name)",
		"--duchy-ids=duchy-\(#Duchies[2].name)",
		"--internal-api-target=" + (#Target & {name: "gcp-kingdom-storage-server"}).target,
		"--port=8080",
	]
	_imagePullPolicy: "Always"
}

kingdom_job: "kingdom-push-spanner-schema-job": {
	apiVersion: "batch/v1"
	kind:       "Job"
	metadata: name: "kingdom-push-spanner-schema-job"
	spec: template: spec: {
		containers: [{
			name:            "push-spanner-schema-container"
			image:           "gcr.io/ads-open-measurement/setup/push-spanner-schema"
			imagePullPolicy: "Always"
			args: [
				"--ignore-already-existing-databases",
				"--drop-databases-first",
				"--databases=kingdom=/app/wfa_measurement_system/src/main/db/gcp/kingdom.sdl",
				"--instance-name=qa-instance",
				"--project-name=ads-open-measurement",
			]
		}]
		restartPolicy: "OnFailure"
	}
}
