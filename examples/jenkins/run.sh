#!/usr/bin/env bash
set -eo pipefail

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
readonly SCRIPT_DIR

readonly TMP_LOG="/tmp/run.$$.log"
readonly BASE_URL="http://localhost:8080"

wait_for_server() {
    local url=$1
    echo -n " waiting for ${url}"
    until curl --output /dev/null --silent --head --fail "$url"; do
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

container_exists() {
    if [[ -z $(docker_compose ps -q) ]]; then
        return 1
    else
        return 0
    fi
}

provision_container() {
    docker_compose up --no-start
}

start_server() {
    announce "Starting docker image"
    docker_compose up -d &>> "$TMP_LOG"

    wait_for_server "${BASE_URL}/queue/api/json"
    echo " done"
}

csrf_crumb() {
    local tmp_cookie_jar="$1"
    curl -u "admin:admin" --cookie-jar "$tmp_cookie_jar" "${BASE_URL}/crumbIssuer/api/xml?xpath=concat(//crumbRequestField,%22:%22,//crumb)"
}

configure_pipeline() {
    local job_config
    local job_name
    local view_config
    local view_name
    local tmp_cookie_jar="/tmp/cookie_jar.$$"
    local crumb
    announce "Configuring pipeline"
    crumb="$(csrf_crumb "$tmp_cookie_jar")"

    (
        cd "$SCRIPT_DIR"/jobs
        for job_config in *; do
            # shellcheck disable=SC2001
            job_name=$( echo "$job_config" | sed s/.xml$// )
            curl --fail -X POST --data-binary "@$job_config" -H "Content-Type: application/xml" -H "$crumb" --cookie "$tmp_cookie_jar" "${BASE_URL}/createItem?name=${job_name}" &>> "$TMP_LOG"
            curl --fail -X POST --data-binary "@$job_config" -H "$crumb" --cookie "$tmp_cookie_jar" "${BASE_URL}/job/${job_name}/config.xml" &>> "$TMP_LOG"
        done
    )
    (
        cd "$SCRIPT_DIR"/views
        for view_config in *; do
            # shellcheck disable=SC2001
            view_name=$( echo "$view_config" | sed s/.xml$// )
            curl --fail -X POST --data-binary "@$view_config" -H "Content-Type: application/xml" -H "$crumb" --cookie "$tmp_cookie_jar" "${BASE_URL}/createView?name=${view_name}" &>> "$TMP_LOG"
            curl --fail -X POST --data-binary "@$view_config" -H "$crumb" --cookie "$tmp_cookie_jar" "${BASE_URL}/view/${view_name}/config.xml" &>> "$TMP_LOG"
        done
    )
    rm "$tmp_cookie_jar"
    echo " done"
}

jenkins_queue_length() {
    curl --silent "${BASE_URL}/queue/api/json" | python3 -c "import json; import sys; print(len(json.loads(sys.stdin.read())['items']))"
}

build_queue_empty() {
    if [[ "$(jenkins_queue_length)" -eq 0 ]]; then
        return 0;
    else
        return 1;
    fi
}

wait_for_pipeline_to_be_schedulable() {
    sleep 2
    until build_queue_empty ; do
        printf '.'
        sleep 5
    done
}

run_builds() {
    local tmp_cookie_jar="/tmp/cookie_jar.$$"
    local crumb
    local run
    crumb="$(csrf_crumb "$tmp_cookie_jar")"

    for run in 1 2 3 4 5; do
        announce "Triggering build run ${run}"
        wait_for_pipeline_to_be_schedulable
        curl --fail -X POST  -H "$crumb" --cookie "$tmp_cookie_jar" "${BASE_URL}/job/Test/build" &>> "$TMP_LOG"
        echo
    done
    rm "$tmp_cookie_jar"
}

goal_start() {
    if ! container_exists; then
        announce "Provisioning docker image"
        echo
        provision_container
        start_server
        configure_pipeline
        run_builds
    else
        start_server
    fi
    echo "Started at ${BASE_URL}"
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
    docker images -q jenkins_jenkins | xargs docker rmi &>> "$TMP_LOG"
    docker images -q jenkins/jenkins | xargs docker rmi &>> "$TMP_LOG"
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
