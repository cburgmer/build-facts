# Build facts

Download build facts from different CI/CD servers in a standard JSON schema.
You then take it from there with your own analytics.

    $ curl -LO https://github.com/cburgmer/build-facts/releases/download/0.4.0/build-facts-0.4.0-standalone.jar
    $ java -jar build-facts-0.4.0-standalone.jar jenkins http://localhost:8080
    Finding all builds for syncing from http://localhost:8080 (starting from 2021-01-06T23:00:00.000Z)...
    {"jobName":"Test","buildId":"1","start":1615151319678,"end":1615151342243,"outcome":"pass","inputs":[{"revision":"9bb731de4f4372a8c3b4e53e7d70cd729b32419c","sourceId":"https://github.com/cburgmer/buildviz.git"}]}
    {"jobName":"Test","buildId":"2","start":1615151342348,"end":1615151344854,"outcome":"pass","inputs":[{"revision":"9bb731de4f4372a8c3b4e53e7d70cd729b32419c","sourceId":"https://github.com/cburgmer/buildviz.git"}]}
    {"jobName":"Deploy","buildId":"1","start":1615151349657,"end":1615151361672,"outcome":"pass","inputs":[{"revision":"9bb731de4f4372a8c3b4e53e7d70cd729b32419c","sourceId":"TEST_GIT_COMMIT"}],"triggeredBy":[{"jobName":"Test","buildId":"1"},{"jobName":"Test","buildId":"2"}]}
    [...]

## Why?

It's hard to innovate when every build server has their own format of reporting
build data. By offering a more standard format, we hope that it becomes
feasible to share build analytics across users of different build servers.
See https://github.com/cburgmer/buildviz for an example on what is possible with
the current set of build properties.

## Features

- Streams standardized build data (specified via JSON Schema)
- Splunk HEC format
- Resume from previous sync
- Output build data to files

### Supported build servers

| Build server | Start, end and outcome | Inputs | Triggered by | Test results |
| ------------ | ---------------------- | ------ | ------------ | ------------ |
| Concourse    | ✓                      | ✓      |              |              |
| GoCD         | ✓                      | ✓      | ✓            | ✓            |
| Jenkins      | ✓                      | ✓      | ✓            | ✓            |
| TeamCity     | ✓                      | ✓      | ✓            | ✓            |

See [BUILD_SERVERS.md](./BUILD_SERVERS.md) for detailed setup.

## JSON schema

[JSON Schema document](./schema.json)

Example:

    {
      "jobName": "Deploy",
      "buildId": "21",
      "start": 1451449853542,
      "end": 1451449870555,
      "outcome": "pass", /* or "fail" */
      "inputs": [{
        "revision": "1eadcdd4d35f9a",
        "sourceId": "git@github.com:cburgmer/buildviz.git"
      }],
      "triggeredBy": [{
        "jobName": "Test",
        "buildId": "42"
      }],
      "testResults": [{
        "name": "Test Suite",
        "children": [{
          "classname": "some.class",
          "name": "A Test",
          "runtime": 2,
          "status": "pass"
        }]
      }]
    }

## Examples

This project ships with examples for every supported build server: [./examples](./examples/).
