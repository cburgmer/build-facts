#!/usr/bin/env bash
set -eo pipefail

readonly SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

readonly TMP_LOG="/tmp/run.$$.log"
readonly COLLECTOR_BASE_URL="https://localhost:8088"

wait_for_server() {
    local url=$1
    echo -n " waiting for ${url}"
    until curl --output /dev/null --silent -XOPTIONS -k --fail -H "Authorization: Splunk 1234567890qwertyuiop" "$url"; do
        printf '.'
        sleep 5
    done
}

announce() {
    local text="$1"
    echo -ne "\033[1;30m"
    echo -n "$text"
    echo -ne "\033[0m"
}

hint_at_logs() {
    # shellcheck disable=SC2181
    if [[ "$?" -ne 0 && -f "$TMP_LOG" ]]; then
        echo
        echo "Logs are in ${TMP_LOG}"
    else
        rm -f "$TMP_LOG"
    fi
}

docker_compose() {
    (
        cd "$SCRIPT_DIR"
        docker-compose "$@"
    )
}

start_server() {
    announce "Starting docker image"
    docker_compose up -d &>> "$TMP_LOG"

    wait_for_server "${COLLECTOR_BASE_URL}/services/collector/ack"
    echo " done"
}

goal_start() {
    start_server
}

goal_stop() {
    announce "Stopping docker image"
    docker_compose stop &>> "$TMP_LOG"
    echo " done"
}

goal_destroy() {
    announce "Destroying docker container"
    docker_compose down &>> "$TMP_LOG"
    echo " done"
}

goal_purge() {
    announce "Purging docker images"
    docker images -q splunk/splunk | xargs docker rmi &>> "$TMP_LOG"
    echo " done"
}

main() {
    trap hint_at_logs EXIT

    if type -t "goal_$1" &>/dev/null; then
        "goal_$1"
    else
        echo "usage: $0 (start|stop|destroy|purge)"
    fi
}

main "$@"
