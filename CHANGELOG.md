# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]
### Fixed
- TeamCity: `triggeredBy` information is now correctly parsed for newer versions of the TeamCity server. Unclear for which version it started silently failing.


## [0.6.1] - 2022-02-12
### Fixed
- The jar contained superfluous files from the development setup increasing the file size.


## [0.6.0] - 2022-02-12
### Added
- Support for `triggeredBy` information from Concourse CI.
