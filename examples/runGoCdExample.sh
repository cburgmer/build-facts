#!/bin/bash

PORT=3333
BUILDVIZ_PATH="http://localhost:${PORT}"

curl --output /dev/null --silent --head --fail "${BUILDVIZ_PATH}"
if [ $? -eq 0 ]; then
    echo "Please stop the application running on port $PORT before continuing"
    exit 1
fi

set -e

echo "This example will download and install GoCD in a VirtualBox and then sync its output to buildviz"
echo
echo "Press any key to continue"

read -n 1

SCRIPT_DIR=$(cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd)

function wait_for_server() {
    URL=$1
    until $(curl --output /dev/null --silent --head --fail $URL); do
        printf '.'
        sleep 5
    done
}


# Install GoCD
echo "Installing GoCD..."

cd "${SCRIPT_DIR}/go"
vagrant up
cd -

# Start buildviz
TMP_DIR="/tmp/buildviz.$$"
LOGGING_PATH="${TMP_DIR}/buildviz.log"

mkdir -p "$TMP_DIR"

echo "Starting buildviz... (sending stdout to $LOGGING_PATH)"
BUILDVIZ_DATA_DIR=$TMP_DIR BUILDVIZ_PIPELINE_NAME="GoCD example" "${SCRIPT_DIR}/../lein" do deps, ring server-headless $PORT > "$LOGGING_PATH" &
SERVER_PID=$!

function clean_up() {
    echo "Taking down Vagrant instance and buildviz..."

    cd "${SCRIPT_DIR}/go"
    vagrant halt
    cd -

    pkill -P $SERVER_PID
    exit 0
}

# Handle Ctrl+C
trap clean_up INT

# Wait
echo "Waiting for buildviz to come up"
wait_for_server "${BUILDVIZ_PATH}"
echo "Waiting for GoCD to come up"
wait_for_server http://localhost:8153/go

# Sync buildviz with the GoCD builds
echo "Syncing job history..."
"${SCRIPT_DIR}/../lein" run -m buildviz.go.sync http://localhost:8153/go --buildviz="${BUILDVIZ_PATH}" --from 2014-06-01

echo "Done..."
echo
echo "Point your browser to ${BUILDVIZ_PATH}"
echo
echo "Later, press any key to stop the server and bring down the vagrant box"

read -n 1

clean_up
