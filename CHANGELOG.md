# Changelog

All notable changes to the nf-prov plugin will be documented here.

See [Keep a Changelog](http://keepachangelog.com/) for recommendations on how to structure this file.

## [1.6.0] - 2025-10-23

- Bump Nextflow 25.10
- Bump nextflow-gradle-plugin 1.0.0-beta.12
- Track remote input files (#45)
- Save WC_SAMPLE output in nf-prov-test as a workflow output (#52)

## [1.5.0] - 2025-09-04

- Use Nextflow Gradle plugin (#42)
- Declare config options with ConfigScope (#44)
- Add workflow outputs to WRROC report (#47)
- Remove legacy provenance format (#48)
- Use plugin registry (#50)

## [1.4.0] - 2025-02-06

- Add Workflow Run RO-Crate format (#39)
- Update test pipeline (#31)
- Deprecate legacy format

## [1.3.0] - 2024-11-05

- Add passthrough options for BCO (#36)

## [1.2.4] - 2024-07-25

- Fix race condition with workflow events (#35)

## [1.2.3] - 2024-07-03

- Fix path handling (#34)

## [1.2.2] - 2024-03-26

- Increase size limits in task DAG renderer
- Add task script to legacy format

## [1.2.1] - 2023-10-27

- Log warning instead of error if no formats are defined

## [1.2.0] - 2023-10-27

- Add task DAG format
- Support multiple formats in the same run
- Infer source URLs for locally staged files

## [1.1.0] - 2023-09-28

- Add BCO format

## [1.0.0] - 2022-12-19

- Initial release
