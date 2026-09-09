#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Licensed to the Ed-Fi Alliance under one or more agreements.
# The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
# See the LICENSE and NOTICES files in the project root for more information.

import importlib.util
import tempfile
import unittest
from pathlib import Path
from xml.etree import ElementTree as ET

spec = importlib.util.spec_from_file_location('qualification', Path(__file__).with_name('verify-cdc-qualification.py'))
qualification = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qualification)


class QualificationGateTests(unittest.TestCase):
    def verify_results(self, cases):
        root = ET.Element('TestRun', xmlns='http://microsoft.com/schemas/VisualStudio/TeamTest/2010')
        results = ET.SubElement(root, 'Results')
        definitions = ET.SubElement(root, 'TestDefinitions')
        for index, (provider, method, outcome) in enumerate(cases):
            test_id = str(index)
            ET.SubElement(results, 'UnitTestResult', testId=test_id, testName=method, outcome=outcome)
            definition = ET.SubElement(definitions, 'UnitTest', id=test_id)
            ET.SubElement(definition, 'TestMethod', name=method,
                          className=f'EdFi.DataManagementService.Backend.Cdc.Tests.Integration.Given_CdcConnectorTelemetryQualification({provider})')
        summary = ET.SubElement(root, 'ResultSummary')
        ET.SubElement(summary, 'Counters', total=str(len(cases)),
                      executed=str(sum(outcome != 'NotExecuted' for _, _, outcome in cases)),
                      passed=str(sum(outcome == 'Passed' for _, _, outcome in cases)))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'telemetry.trx'
            ET.ElementTree(root).write(path)
            qualification.verify(path)

    def cases(self):
        method = 'It_qualifies_the_pinned_exporter_with_real_streaming_and_replaces_task_and_worker_metrics'
        return [(provider, method, 'Passed') for provider in ('Postgresql', 'SqlServer')]

    def test_accepts_both_providers_with_identical_display_names(self):
        self.verify_results(self.cases())

    def test_accepts_additional_passing_cases(self):
        self.verify_results(self.cases() + [('Postgresql', 'Another_case', 'Passed')])

    def test_rejects_duplicate_provider_in_place_of_missing_provider(self):
        with self.assertRaisesRegex(ValueError, 'Missing passing provider'):
            self.verify_results([self.cases()[1], self.cases()[1]])

    def test_rejects_other_method_in_place_of_required_qualification(self):
        with self.assertRaisesRegex(ValueError, 'Missing passing provider'):
            self.verify_results([self.cases()[0], ('SqlServer', 'Another_case', 'Passed')])

    def test_rejects_skipped_or_failed_additional_cases(self):
        for outcome in ('NotExecuted', 'Failed'):
            with self.subTest(outcome=outcome), self.assertRaises(ValueError):
                self.verify_results(self.cases() + [('Postgresql', 'Another_case', outcome)])

    def test_rejects_empty_run(self):
        with self.assertRaises(ValueError):
            self.verify_results([])


if __name__ == '__main__':
    unittest.main()
