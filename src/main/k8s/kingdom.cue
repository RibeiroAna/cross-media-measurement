// Copyright 2020 The Cross-Media Measurement Authors
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

package k8s

import ("strings")

#Kingdom: {
	_verbose_grpc_logging: "true" | "false"

	_spanner_schema_push_flags: [...string]
	_spanner_flags: [...string]

	_images: [Name=_]: string
	_kingdom_image_pull_policy: string
	_kingdom_secret_name:       string

	_duchy_info_config_flag:                 "--duchy-info-config=/var/run/secrets/files/duchy_rpc_config.textproto"
	_duchy_id_config_flag:                   "--duchy-id-config=/var/run/secrets/files/duchy_id_config.textproto"
	_llv2_protocol_config_config:            "--llv2-protocol-config-config=/var/run/secrets/files/llv2_protocol_config_config.textproto"
	_kingdom_tls_cert_file_flag:             "--tls-cert-file=/var/run/secrets/files/kingdom_tls.pem"
	_kingdom_tls_key_file_flag:              "--tls-key-file=/var/run/secrets/files/kingdom_tls.key"
	_kingdom_cert_collection_file_flag:      "--cert-collection-file=/var/run/secrets/files/all_root_certs.pem"
	_debug_verbose_grpc_client_logging_flag: "--debug-verbose-grpc-client-logging=\(_verbose_grpc_logging)"
	_debug_verbose_grpc_server_logging_flag: "--debug-verbose-grpc-server-logging=\(_verbose_grpc_logging)"

	_internal_api_target_flag:    "--internal-api-target=" + (#Target & {name: "gcp-kingdom-data-server"}).target
	_internal_api_cert_host_flag: "--internal-api-cert-host=localhost"

	kingdom_service: [Name=_]: #GrpcService & {
		_name:   Name
		_system: "kingdom"
	}

	kingdom_service: {
		"gcp-kingdom-data-server": {}
		"system-api-server": {}
		"v2alpha-public-api-server": {}
	}

	kingdom_job: "kingdom-push-spanner-schema-job": {
		apiVersion: "batch/v1"
		kind:       "Job"
		metadata: {
			name: "kingdom-push-spanner-schema-job"
			labels: "app.kubernetes.io/name": #AppName
		}
		spec: template: spec: {
			containers: [{
				name:            "push-spanner-schema-container"
				image:           _images[name]
				imagePullPolicy: _kingdom_image_pull_policy
				args:            [
							"--databases=kingdom=/app/wfa_measurement_system/src/main/kotlin/org/wfanet/measurement/kingdom/deploy/gcloud/spanner/kingdom.sdl",
				] + _spanner_schema_push_flags
			}]
			restartPolicy: "OnFailure"
		}
	}

	kingdom_deployment: [Name=_]: #Deployment & {
		_name:            strings.TrimSuffix(Name, "-deployment")
		_secretName:      _kingdom_secret_name
		_system:          "kingdom"
		_image:           _images[_name]
		_imagePullPolicy: _kingdom_image_pull_policy
	}

	kingdom_deployment: {
		"gcp-kingdom-data-server-deployment": #ServerDeployment & {
			_args: [
				_duchy_info_config_flag,
				_duchy_id_config_flag,
				_kingdom_tls_cert_file_flag,
				_kingdom_tls_key_file_flag,
				_kingdom_cert_collection_file_flag,
				_debug_verbose_grpc_server_logging_flag,
				"--port=8443",
			] + _spanner_flags
		}

		"system-api-server-deployment": #ServerDeployment & {
			_args: [
				_debug_verbose_grpc_client_logging_flag,
				_debug_verbose_grpc_server_logging_flag,
				_duchy_info_config_flag,
				_kingdom_tls_cert_file_flag,
				_kingdom_tls_key_file_flag,
				_kingdom_cert_collection_file_flag,
				_internal_api_target_flag,
				_internal_api_cert_host_flag,
				"--port=8443",
			]
			_dependencies: ["gcp-kingdom-data-server"]
		}

		"v2alpha-public-api-server-deployment": #ServerDeployment & {
			_args: [
				_debug_verbose_grpc_client_logging_flag,
				_debug_verbose_grpc_server_logging_flag,
				_llv2_protocol_config_config,
				_kingdom_tls_cert_file_flag,
				_kingdom_tls_key_file_flag,
				_kingdom_cert_collection_file_flag,
				_internal_api_target_flag,
				_internal_api_cert_host_flag,
				"--port=8443",
			]
			_dependencies: ["gcp-kingdom-data-server"]
		}
	}

	kingdom_internal_network_policies: [Name=_]: #NetworkPolicy & {
		_name: Name
	}
	kingdom_internal_network_policies: {
		"interal-data-server": #NetworkPolicy & {
			_sourceMatchLabels: [
				"v2alpha-public-api-server-app",
				"system-api-server-app",
			]
			_destinationMatchLabels: "gcp-kingdom-data-server-app"
		}
	}
}
