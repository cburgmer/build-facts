#!/bin/bash
set -eo pipefail

readonly SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

readonly MAPPING_SOURCE="${SCRIPT_DIR}/concourse.tar.gz"

readonly WIREMOCK_PORT="3340"

readonly WIREMOCK_BASE_URL="http://localhost:${WIREMOCK_PORT}"

readonly MAPPING_TMP_DIR="/tmp/record.wiremock.$$"

start_wiremock() {
    mkdir "$MAPPING_TMP_DIR"
    tar -xzf "$MAPPING_SOURCE" -C "$MAPPING_TMP_DIR"

    "${SCRIPT_DIR}/run_wiremock.sh" install
    ROOT_DIR="$MAPPING_TMP_DIR" PORT="$WIREMOCK_PORT" "${SCRIPT_DIR}/run_wiremock.sh" start
}

stop_wiremock() {
    "${SCRIPT_DIR}/run_wiremock.sh" stop

    rm -rf "$MAPPING_TMP_DIR"
}

sync_builds() {
    EXPERIMENTAL_EVENTS=true JVM_OPTS="-Duser.home=${SCRIPT_DIR}" "${SCRIPT_DIR}/../../lein" run -m build-facts.main concourse mock-auth --from "2021-01-01"
}

ensure_user_agent() {
    local count_request='{"method": "GET", "url": "/api/v1/user", "headers": {"User-Agent": {"matches": "build-facts.*"}}}'
    local count_response
    count_response=$(echo "$count_request" | curl -s -X POST -d@- "${WIREMOCK_BASE_URL}/__admin/requests/count")
    if ! grep '"count" : 1' <<<"$count_response" > /dev/null; then
        echo "User agent not found:"
        echo "$count_response"
        exit 1
    fi

}

ensure_authorization() {
    local count_request='{"method": "GET", "url": "/api/v1/user", "headers": {"Authorization": {"matches": "Bearer mock-bearer-token"}}}'
    local count_response
    count_response=$(echo "$count_request" | curl -s -X POST -d@- "${WIREMOCK_BASE_URL}/__admin/requests/count")
    if ! grep '"count" : 1' <<<"$count_response" > /dev/null; then
        echo "Basic auth not found:"
        echo "$count_response"
        exit 1
    fi

}

clean_up() {
    stop_wiremock
}

ensure_mappings() {
    if [[ ! -e "$MAPPING_SOURCE" ]]; then
        echo "Please run ./record_concourse.sh first"
        exit 1
    fi
}

main() {
    ensure_mappings

    # Handle Ctrl+C and errors
    trap clean_up EXIT

    start_wiremock

    sync_builds

    ensure_user_agent
    ensure_authorization
}

main
