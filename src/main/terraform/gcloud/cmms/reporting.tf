# Copyright 2023 The Cross-Media Measurement Authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

module "reporting_cluster" {
  source = "../modules/cluster"

  name       = local.reporting_cluster_name
  location   = local.cluster_location
  secret_key = module.common.cluster_secret_key
}

data "google_container_cluster" "reporting" {
  name     = local.reporting_cluster_name
  location = local.cluster_location

  # Defer reading of cluster resource until it exists.
  depends_on = [module.reporting_cluster]
}

module "reporting_default_node_pool" {
  source = "../modules/node-pool"

  name            = "default"
  cluster         = data.google_container_cluster.reporting
  service_account = module.common.cluster_service_account
  machine_type    = "e2-small"
  max_node_count  = 4
}

provider "kubernetes" {
  # Due to the fact that this is using interpolation, the cluster must already exist.
  # See https://registry.terraform.io/providers/hashicorp/kubernetes/2.20.0/docs

  alias = "reporting"
  host  = "https://${data.google_container_cluster.reporting.endpoint}"
  token = data.google_client_config.default.access_token
  cluster_ca_certificate = base64decode(
    data.google_container_cluster.reporting.master_auth[0].cluster_ca_certificate,
  )
}

module "reporting" {
  source = "../modules/reporting"

  providers = {
    google     = google
    kubernetes = kubernetes.reporting
  }

  postgres_instance          = google_sql_database_instance.postgres
  monitoring_service_account = module.common.monitoring_service_account
}
