#!/bin/bash
set -eo pipefail

readonly SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

readonly DEPS_DIR="${SCRIPT_DIR}/node_modules"
readonly TMP_LOG="/tmp/run.sse_closing_proxy.log"
readonly PID_FILE="${SCRIPT_DIR}/sse_closing_proxy.pid"

announce() {
    local text="$1"
    echo -ne "\033[1;30m"
    echo -n "$text"
    echo -ne "\033[0m"
}

hint_at_logs() {
    # shellcheck disable=SC2181
    if [[ "$?" -ne 0 ]]; then
        echo
        echo "Logs are in ${TMP_LOG}"
    fi
}

goal_install() {
    announce "Installing dependencies"
    if [ -d "$DEPS_DIR" ]; then
        echo " already there"
    else
        (
            mkdir "$DEPS_DIR"
            cd "$DEPS_DIR/.."
            npm i http-proxy@1.18.1 > "$TMP_LOG"
        )
        echo " done"
        rm "$TMP_LOG"
    fi
}

is_running() {
    local pid

    if [[ ! -f "$PID_FILE" ]]; then
        return 1
    fi

    read -r pid < "$PID_FILE"
    ps -p "$pid" > /dev/null
}

goal_start() {
    announce "Starting sse_closing_proxy"

    if [[ ! -d "$DEPS_DIR" ]]; then
        echo "Run $0 install first"
        exit 1
    fi

    if is_running; then
        echo " another running instance found"
        exit 1
    fi

    (
        cd "$DEPS_DIR/.."
        node sse_closing_proxy.js > "$TMP_LOG" &
        echo "$!" > "$PID_FILE"
    )

    echo " done"
}

goal_stop() {
    local pid

    announce "Stopping sse_closing_proxy"
    if ! is_running; then
        echo " no running instance found, nothing to do"
        return
    fi

    read -r pid < "$PID_FILE"
    kill "$pid" > "$TMP_LOG"
    rm "$PID_FILE"

    echo " done"
    rm "$TMP_LOG"
}

main() {
    trap hint_at_logs EXIT

    if type -t "goal_$1" &>/dev/null; then
        "goal_$1"
    else
        echo "usage: $0 (start|stop|install)"
    fi
}

main "$@"
