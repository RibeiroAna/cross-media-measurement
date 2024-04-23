# Copyright 2020 The Measurement System Authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""
Make variable strings used by build targets in project.
"""

MEASUREMENT_SYSTEM_REPO = "https://github.com/world-federation-of-advertisers/cross-media-measurement"

# Settings for the repositories where container images are stored.
IMAGE_REPOSITORY_SETTINGS = struct(
    # The container registry for targets which push or pull container images.
    #
    # For example, `gcr.io` for Google Cloud Container Registry or `docker.io`
    # for DockerHub.
    container_registry = "$(container_registry)",

    # Common prefix of container image repositories.
    repository_prefix = "$(image_repo_prefix)",

    # Common tag for container images.
    image_tag = "$(image_tag)",
)

# Settings for test Kubernetes deployments.
TEST_K8S_SETTINGS = struct(
    # Resource name of the aggregator Duchy's Certificate.
    aggregator_cert_name = "$(aggregator_cert_name)",
    # Resource name of the 1st worker Duchy's Certificate.
    worker1_cert_name = "$(worker1_cert_name)",
    # Resource name of the 2nd worker Duchy's Certificate.
    worker2_cert_name = "$(worker2_cert_name)",
    mc_name = "$(mc_name)",
    mc_api_key = "$(mc_api_key)",
    mc_cert_name = "$(mc_cert_name)",
    edp1_name = "$(edp1_name)",
    edp1_cert_name = "$(edp1_cert_name)",
    edp2_name = "$(edp2_name)",
    edp2_cert_name = "$(edp2_cert_name)",
    edp3_name = "$(edp3_name)",
    edp3_cert_name = "$(edp3_cert_name)",
    edp4_name = "$(edp4_name)",
    edp4_cert_name = "$(edp4_cert_name)",
    edp5_name = "$(edp5_name)",
    edp5_cert_name = "$(edp5_cert_name)",
    edp6_name = "$(edp6_name)",
    edp6_cert_name = "$(edp6_cert_name)",
    db_secret_name = "$(k8s_db_secret_name)",
    mc_config_secret_name = "$(k8s_mc_config_secret_name)",
    grafana_secret_name = "$(k8s_grafana_secret_name)",
)

GCLOUD_SETTINGS = struct(
    project = "$(google_cloud_project)",
    spanner_instance = "$(spanner_instance)",
    postgres_instance = "$(postgres_instance)",
    postgres_region = "$(postgres_region)",
)

AWS_SETTINGS = struct(
    postgres_host = "$(postgres_host)",
    postgres_port = "$(postgres_port)",
    postgres_credential_secret_name = "$(postgres_credential_secret_name)",
    postgres_region = "$(postgres_region)",
    s3_bucket = "$(s3_bucket)",
    s3_region = "$(s3_region)",
    computation_control_server_eips = "$(computation_control_server_eips)",
    amp_ingest_endpoint = "$(amp_ingest_endpoint)",
    amp_region = "$(amp_region)",
)

# Settings for Kingdom Kubernetes deployments.
KINGDOM_K8S_SETTINGS = struct(
    public_api_target = "$(kingdom_public_api_target)",
    system_api_target = "$(kingdom_system_api_target)",
    public_api_address_name = "$(kingdom_public_api_address_name)",
    system_api_address_name = "$(kingdom_system_api_address_name)",
)

# Settings for Duchy Kubernetes deployments.
DUCHY_K8S_SETTINGS = struct(
    certificate_id = "$(duchy_cert_id)",
    storage_bucket = "$(duchy_storage_bucket)",
    worker1_public_api_target = "$(worker1_public_api_target)",
    worker2_public_api_target = "$(worker2_public_api_target)",
    aggregator_system_api_target = "$(aggregator_system_api_target)",
    worker1_system_api_target = "$(worker1_system_api_target)",
    worker2_system_api_target = "$(worker2_system_api_target)",
    worker1_id = "$(worker1_id)",
    worker2_id = "$(worker2_id)",
    public_api_address_name = "$(duchy_public_api_address_name)",
    system_api_address_name = "$(duchy_system_api_address_name)",
)

# Settings for Reporting Kubernetes deployments.
REPORTING_K8S_SETTINGS = struct(
    public_api_address_name = "$(reporting_public_api_address_name)",
)

# Settings for simulator Kubernetes deployments.
SIMULATOR_K8S_SETTINGS = struct(
    mc_name = "$(mc_name)",
    mc_api_key = "$(mc_api_key)",
    edp1_name = "$(edp1_name)",
    edp1_cert_name = "$(edp1_cert_name)",
    edp2_name = "$(edp2_name)",
    edp2_cert_name = "$(edp2_cert_name)",
    edp3_name = "$(edp3_name)",
    edp3_cert_name = "$(edp3_cert_name)",
    edp4_name = "$(edp4_name)",
    edp4_cert_name = "$(edp4_cert_name)",
    edp5_name = "$(edp5_name)",
    edp5_cert_name = "$(edp5_cert_name)",
    edp6_name = "$(edp6_name)",
    edp6_cert_name = "$(edp6_cert_name)",
    bigquery_dataset = "$(bigquery_dataset)",
    bigquery_table = "$(bigquery_table)",
)

# Settings for Grafana Kubernetes deployments.
GRAFANA_K8S_SETTINGS = struct(
    secret_name = "$(k8s_grafana_secret_name)",
)

# Config for Panel Exchange Client Example Daemon.
EXAMPLE_PANEL_EXCHANGE_CLIENT_DAEMON_CONFIG = struct(
    edp_name = "$(edp_name)",
    mp_name = "$(mp_name)",
)

# Settings for deploying tests to Google Cloud.
PANEL_EXCHANGE_CLIENT_TEST_GOOGLE_CLOUD_SETTINGS = struct(
    secret_name = "$(k8s_secret_name)",
    cloud_storage_project = "$(cloud_storage_project)",
    cloud_storage_bucket = "$(cloud_storage_bucket)",
)
