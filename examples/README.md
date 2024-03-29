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

#### Run an build server setup

Bring up a server (adapt with the desired build server)

    $ ./jenkins/run.sh start

Stop a server

    $ ./jenkins/run.sh stop

(Re-running the examples will re-use the provisioned containers.)

Remove container and purge images

    $ ./jenkins/run.sh destroy
    $ ./jenkins/run.sh purge


#### Sync data

##### Jenkins

Sync data

    $ curl -LO https://github.com/cburgmer/build-facts/releases/download/0.6.1/build-facts-0.6.1-standalone.jar
    $ java -jar build-facts-0.6.1-standalone.jar jenkins http://localhost:8080

##### Concourse

Sync data

    $ curl -LO https://github.com/cburgmer/build-facts/releases/download/0.6.1/build-facts-0.6.1-standalone.jar
    $ fly login --target build-facts -c http://localhost:8080 -u user -p password
    $ java -jar build-facts-0.6.1-standalone.jar concourse build-facts 'http://localhost:8080'

##### TeamCity

Sync data

    $ curl -LO https://github.com/cburgmer/build-facts/releases/download/0.6.1/build-facts-0.6.1-standalone.jar
    $ TEAMCITY_USER=admin TEAMCITY_PASSWORD=admin java -jar build-facts-0.6.1-standalone.jar teamcity 'http://localhost:8111' -p SimpleSetup


### Splunk

Start up Splunk and a build server of your choice

    $ ./splunk/run.sh start
    $ ./jenkins/run.sh start

Sync data from the build server into Splunk

    $ java -jar build-facts-0.6.1-standalone.jar jenkins http://localhost:8080 --splunk \
        curl -k -d@- -H 'Transfer-Encoding: chunked' -H "Authorization: Splunk 1234567890qwertyuiop" \
        'https://localhost:8088/services/collector'

An example [dashboard exists](./splunk/dashboard.xml) and can be copied in via the "Source" edit mode.
