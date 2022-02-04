#!/usr/bin/env bash


## NOTE
# This script is meant to be run on github action when a tag is created for this repo.
# It creates release assets which are then uploaded to github release by subsequent github action step.

mkdir /tmp/release-artifacts

# Integration
# RTM.zip
zip /tmp/release-artifacts/integration-requirement-test-mapping.zip integration/target/RTM/*

# IRIS
# RTM.zip
zip /tmp/release-artifacts/iris-requirement-test-mapping.zip iris/target/RTM/*
# scoverage-report.zip
zip /tmp/release-artifacts/iris-scoverage-report.zip iris/target/scala-2.13/scoverage-report/*

# WFOS
# RTM zip
zip /tmp/release-artifacts/wfos-requirement-test-mapping.zip wfos/target/RTM/*
# scoverage.zip
zip /tmp/release-artifacts/wfos-scoverage-report.zip wfos/target/scala-2.13/scoverage-report/*


# Find all xml files in all target folders & create wfos-test-reports.zip
mkdir /tmp/wfos-test-reports
find wfos -name "TEST-*.xml" -exec cp -p "{}" /tmp/wfos-test-reports/ \;
zip /tmp/release-artifacts/wfos-test-reports.zip /tmp/wfos-test-reports/*
# wfos-test-reports.html
junit-merge -d /tmp/wfos-test-reports -o /tmp/wfos-test-report.xml
junit-viewer --input=/tmp/wfos-test-report.xml --output=/tmp/release-artifacts/wfos-test-reports.html


# Find all xml files in all target folders & create integration-test-reports.zip
mkdir /tmp/integration-test-reports
find integration -name "TEST-*.xml" -exec cp -p "{}" /tmp/integration-test-reports/ \;
zip /tmp/release-artifacts/integration-test-reports.zip /tmp/integration-test-reports/*
# wfos-test-reports.html
junit-merge -d /tmp/integration-test-reports -o /tmp/integration-test-report.xml
junit-viewer --input=/tmp/integration-test-report.xml --output=/tmp/release-artifacts/integration-test-reports.html


# Find all xml files in all target folders & create iris-test-reports.zip
mkdir /tmp/iris-test-reports
find iris -name "TEST-*.xml" -exec cp -p "{}" /tmp/iris-test-reports/ \;
zip /tmp/release-artifacts/iris-test-reports.zip /tmp/iris-test-reports/*
# wfos-test-reports.html
junit-merge -d /tmp/iris-test-reports -o /tmp/iris-test-report.xml
junit-viewer --input=/tmp/iris-test-report.xml --output=/tmp/release-artifacts/iris-test-reports.html