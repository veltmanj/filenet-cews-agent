#!/bin/sh

set -eu

resolve_docker_host() {
    if [ -n "${DOCKER_HOST:-}" ]; then
        printf '%s\n' "$DOCKER_HOST"
        return 0
    fi

    if ! command -v docker >/dev/null 2>&1; then
        return 1
    fi

    docker_host=$(docker context inspect --format '{{(index .Endpoints "docker").Host}}' 2>/dev/null || true)
    if [ -n "$docker_host" ] && [ "$docker_host" != "<no value>" ]; then
        printf '%s\n' "$docker_host"
        return 0
    fi

    return 1
}

resolve_docker_api_version() {
    if [ -n "${DOCKER_API_VERSION:-}" ]; then
        printf '%s\n' "$DOCKER_API_VERSION"
        return 0
    fi

    if ! command -v docker >/dev/null 2>&1; then
        return 1
    fi

    docker_api_version=$(docker version --format '{{.Server.APIVersion}}' 2>/dev/null || true)
    if [ -n "$docker_api_version" ] && [ "$docker_api_version" != "<no value>" ]; then
        printf '%s\n' "$docker_api_version"
        return 0
    fi

    return 1
}

if [ "$#" -eq 0 ]; then
    set -- verify
fi

TESTCONTAINERS_STRATEGY=org.testcontainers.dockerclient.EnvironmentAndSystemPropertyClientProviderStrategy
DOCKER_ENDPOINT=$(resolve_docker_host || true)
DOCKER_API=$(resolve_docker_api_version || true)

if [ -n "$DOCKER_ENDPOINT" ]; then
    export DOCKER_HOST="$DOCKER_ENDPOINT"
    if [ -n "$DOCKER_API" ]; then
        export DOCKER_API_VERSION="$DOCKER_API"
        exec ./mvnw -q -Pliberty-e2e \
            -Ddocker.client.strategy="$TESTCONTAINERS_STRATEGY" \
            -Ddocker.host="$DOCKER_ENDPOINT" \
            -Dapi.version="$DOCKER_API" \
            "$@"
    fi

    exec ./mvnw -q -Pliberty-e2e \
        -Ddocker.client.strategy="$TESTCONTAINERS_STRATEGY" \
        -Ddocker.host="$DOCKER_ENDPOINT" \
        "$@"
fi

if [ -n "$DOCKER_API" ]; then
    export DOCKER_API_VERSION="$DOCKER_API"
    exec ./mvnw -q -Pliberty-e2e \
        -Ddocker.client.strategy="$TESTCONTAINERS_STRATEGY" \
        -Dapi.version="$DOCKER_API" \
        "$@"
fi

exec ./mvnw -q -Pliberty-e2e \
    -Ddocker.client.strategy="$TESTCONTAINERS_STRATEGY" \
    "$@"