#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Licensed to the Ed-Fi Alliance under one or more agreements.
# The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
# See the LICENSE and NOTICES files in the project root for more information.

"""Require the telemetry qualification case for each provider, and no non-passing cases."""
import sys
import xml.etree.ElementTree as ET


def verify(path):
    root = ET.parse(path).getroot()
    ns = {'t': 'http://microsoft.com/schemas/VisualStudio/TeamTest/2010'}
    counters = root.find('t:ResultSummary/t:Counters', ns).attrib
    results = root.findall('t:Results/t:UnitTestResult', ns)
    if not results or any(int(counters[name]) != len(results) for name in ('total', 'executed', 'passed')):
        raise ValueError(f'Every selected qualification case must execute and pass: {counters}')
    definitions = {
        test.attrib['id']: test.find('t:TestMethod', ns).attrib
        for test in root.findall('t:TestDefinitions/t:UnitTest', ns)
    }
    required_method = 'It_qualifies_the_pinned_exporter_with_real_streaming_and_replaces_task_and_worker_metrics'
    required_classes = {
        f'EdFi.DataManagementService.Backend.Cdc.Tests.Integration.Given_CdcConnectorTelemetryQualification({provider})'
        for provider in ('Postgresql', 'SqlServer')
    }
    qualified_classes = set()
    for result in results:
        if result.attrib['outcome'] != 'Passed':
            raise ValueError(f'Qualification case did not pass: {result.attrib}')
        definition = definitions[result.attrib['testId']]
        if definition['name'] == required_method:
            qualified_classes.add(definition['className'])
    missing = required_classes - qualified_classes
    if missing:
        raise ValueError(f'Missing passing provider qualification cases: {sorted(missing)}')


if __name__ == '__main__':
    verify(sys.argv[1])
    print('Postgresql and SqlServer telemetry qualification passed; no selected cases skipped or failed.')
