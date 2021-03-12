#!/bin/bash
set -eo pipefail

readonly SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

readonly MAPPING_TARGET="${SCRIPT_DIR}/concourse.tar.gz"
readonly EXAMPLE_DIR="${SCRIPT_DIR}/../../examples/concourse"

readonly WIREMOCK_PORT="3340"

readonly WIREMOCK_BASE_URL="http://localhost:${WIREMOCK_PORT}"
readonly CONCOURSE_BASE_URL="http://localhost:8080"
readonly SYNC_URL="$WIREMOCK_BASE_URL"
readonly CONCOURSE_TARGET=build-data-record

readonly MAPPING_TMP_DIR="/tmp/record.wiremock.$$"


extract_base_url() {
    sed 's/^\(.*\/\/[^\/]*\).*$/\1/'
}

start_container() {
    "$EXAMPLE_DIR/run.sh" start
}

stop_container() {
    "$EXAMPLE_DIR/run.sh" stop
}

start_wiremock() {
    "${SCRIPT_DIR}/run_wiremock.sh" install
    mkdir -p "$MAPPING_TMP_DIR"
    ROOT_DIR="$MAPPING_TMP_DIR" PORT="$WIREMOCK_PORT" "${SCRIPT_DIR}/run_wiremock.sh" start

    echo "{\"targetBaseUrl\": \"$CONCOURSE_BASE_URL\", \"repeatsAsScenarios\": false}" \
        | curl --fail --silent --output /dev/null -X POST -d@- "${WIREMOCK_BASE_URL}/__admin/recordings/start"
}

stop_wiremock() {
    curl --fail --silent --output /dev/null -X POST "${WIREMOCK_BASE_URL}/__admin/recordings/stop"
    cd "$MAPPING_TMP_DIR"
    tar -czf "$MAPPING_TARGET" ./*
    cd - > /dev/null

    "${SCRIPT_DIR}/run_wiremock.sh" stop

    echo "Recorded in ${MAPPING_TARGET}"

    rm -rf "$MAPPING_TMP_DIR"
}

login() {
    local fly_bin="/tmp/fly.$$"
    local os_name
    os_name="$(uname -s)"

    curl -L "${CONCOURSE_BASE_URL}/api/v1/cli?arch=amd64&platform=${os_name}" -o "$fly_bin"
    chmod a+x "$fly_bin"

    "$fly_bin" -t "$CONCOURSE_TARGET" login -c "$SYNC_URL" -u user -p password
    rm "$fly_bin"
}

sync_builds() {
    "${SCRIPT_DIR}/../../lein" run -m buildviz.main concourse "$CONCOURSE_TARGET"
}

clean_up() {
    stop_container
    stop_wiremock
}

ensure_empty_mappings() {
    if [[ -e "$MAPPING_TARGET" ]]; then
        echo "Please remove ${MAPPING_TARGET} first"
        exit 1
    fi
}

main() {
    ensure_empty_mappings

    # Handle Ctrl+C and errors
    trap clean_up EXIT

    start_container
    start_wiremock
    login

    sync_builds
}

main
