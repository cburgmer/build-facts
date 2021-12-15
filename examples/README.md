## Examples

### Integration with CI servers

These demos show integration with popular CI servers. Installed via Docker, they
come with a minimal pipeline pre-configured.

- Concourse
- GoCD
- Jenkins
- TeamCity

In addition there is a Splunk setup which can be used as a target
for synced build events.

### How to

Bring up a server (adapt with the desired build server)

    $ ./jenkins/run.sh start

Sync data

    $ curl -LO https://github.com/cburgmer/build-facts/releases/download/0.5.4/build-facts-0.5.4-standalone.jar
    $ java -jar build-facts-0.5.4-standalone.jar jenkins http://localhost:8080

Stop a server

    $ ./jenkins/run.sh stop

(Re-running the examples will re-use the provisioned containers.)

Remove container and purge images

    $ ./jenkins/run.sh destroy
    $ ./jenkins/run.sh purge


### Splunk

Start up Splunk and a build server of your choice

    $ ./splunk/run.sh start
    $ ./jenkins/run.sh start

Sync data from the build server into Splunk

    $ java -jar build-facts-0.5.4-standalone.jar jenkins http://localhost:8080 --splunk \
        curl -k -d@- -H 'Transfer-Encoding: chunked' -H "Authorization: Splunk 1234567890qwertyuiop" \
        'https://localhost:8088/services/collector'

An example [dashboard exists](./splunk/dashboard.xml) and can be copied in via the "Source" edit mode.
