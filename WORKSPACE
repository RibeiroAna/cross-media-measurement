workspace(name = "wfa_measurement_system")

load("//build:repositories.bzl", "wfa_measurement_system_repositories")

wfa_measurement_system_repositories()

load("@wfa_common_cpp//build:common_cpp_repositories.bzl", "common_cpp_repositories")

common_cpp_repositories()

load("@wfa_common_cpp//build:common_cpp_deps.bzl", "common_cpp_deps")

common_cpp_deps()

load("@wfa_rules_cue//cue:repositories.bzl", "rules_cue_dependencies")

rules_cue_dependencies()

# TODO(@renjiez): Update grpc version in common-jvm to address abseil version
# discrepancy with common-cpp.
load("@wfa_common_jvm//build:common_jvm_repositories.bzl", "common_jvm_repositories")

common_jvm_repositories()

load("@wfa_common_jvm//build:common_jvm_deps.bzl", "common_jvm_deps")

common_jvm_deps()

# Maven
load(
    "@wfa_common_jvm//build:common_jvm_maven.bzl",
    "COMMON_JVM_EXCLUDED_ARTIFACTS",
    "COMMON_JVM_MAVEN_OVERRIDE_TARGETS",
    "common_jvm_maven_artifacts_dict",
)
load("@rules_jvm_external//:defs.bzl", "maven_install")
load("@wfa_common_jvm//build/maven:artifacts.bzl", "artifacts")

MAVEN_ARTIFACTS_DICT = dict(common_jvm_maven_artifacts_dict().items() + {
    "software.aws.rds:aws-postgresql-jdbc": "0.1.0",
    "org.projectnessie.cel:cel-core": "0.3.11",
    "io.opentelemetry:opentelemetry-api": "1.19.0",
    "io.opentelemetry:opentelemetry-sdk": "1.19.0",
    "io.opentelemetry:opentelemetry-exporter-otlp": "1.19.0",
    "io.opentelemetry:opentelemetry-semconv": "1.19.0-alpha",
    "io.kubernetes:client-java": "16.0.0",
    "io.kubernetes:client-java-extended": "16.0.0",
}.items())

maven_install(
    artifacts = artifacts.dict_to_list(MAVEN_ARTIFACTS_DICT),
    excluded_artifacts = COMMON_JVM_EXCLUDED_ARTIFACTS,
    fetch_sources = True,
    generate_compat_repositories = True,
    override_targets = COMMON_JVM_MAVEN_OVERRIDE_TARGETS,
    repositories = [
        "https://repo.maven.apache.org/maven2/",
    ],
)

load("@bazel_gazelle//:deps.bzl", "go_repository")

go_repository(
    name = "org_golang_google_grpc_cmd_protoc_gen_go_grpc",
    importpath = "google.golang.org/grpc/cmd/protoc-gen-go-grpc",
    sum = "h1:TLkBREm4nIsEcexnCjgQd5GQWaHcqMzwQV0TX9pq8S0=",
    version = "v1.2.0",
)

load("@wfa_common_jvm//build:common_jvm_extra_deps.bzl", "common_jvm_extra_deps")

common_jvm_extra_deps()
