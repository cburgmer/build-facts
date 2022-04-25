FROM jetbrains/teamcity-minimal-agent:2021.2.3

USER root

RUN apt-get update && apt-get -qy install ruby

RUN gem install rake -v 13 && gem install rspec -v 3.11 && gem install rspec_junit_formatter

USER buildagent
