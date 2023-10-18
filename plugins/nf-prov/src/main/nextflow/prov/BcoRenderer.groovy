/*
 * Copyright 2023, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nextflow.prov

import java.nio.file.Files
import java.nio.file.Path
import java.time.format.DateTimeFormatter

import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import nextflow.Session
import nextflow.processor.TaskRun
import nextflow.script.WorkflowMetadata
import nextflow.util.CacheHelper

/**
 * Renderer for the BioCompute Object (BCO) format.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class BcoRenderer implements Renderer {

    private Path path

    private boolean overwrite

    @Delegate
    private PathNormalizer normalizer

    BcoRenderer(Map opts) {
        path = opts.file as Path
        overwrite = opts.overwrite as Boolean

        ProvHelper.checkFileOverwrite(path, overwrite)
    }

    @Override
    void render(Session session, Set<TaskRun> tasks, Map<Path,Path> workflowOutputs) {
        // get workflow inputs
        final taskLookup = ProvHelper.getTaskLookup(tasks)
        final workflowInputs = ProvHelper.getWorkflowInputs(tasks, taskLookup)

        // get workflow metadata
        final metadata = session.workflowMetadata
        this.normalizer = new PathNormalizer(metadata)

        final manifest = metadata.manifest
        final nextflowMeta = metadata.nextflow

        final dateCreated = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(metadata.start)
        final authors = (manifest.author ?: '').tokenize(',')*.trim()
        final nextflowVersion = nextflowMeta.version.toString()
        final params = session.config.params as Map

        // create BCO manifest
        final bco = [
            "object_id": null,
            "spec_version": null,
            "etag": null,
            "provenance_domain": [
                "name": manifest.name ?: "",
                "version": manifest.version ?: "",
                "created": dateCreated,
                "modified": dateCreated,
                "contributors": authors.collect( name -> [
                    "contribution": ["authoredBy"],
                    "name": name
                ] ),
                "license": ""
            ],
            "usability_domain": [],
            "extension_domain": [],
            "description_domain": [
                "keywords": [],
                "platform": ["Nextflow"],
                "pipeline_steps": tasks.sort( (task) -> task.id ).collect { task -> [
                    "step_number": task.id,
                    "name": task.hash.toString(),
                    "description": task.name,
                    "input_list": task.getInputFilesMap().collect { name, source -> [
                        "uri": normalizePath(source)
                    ] },
                    "output_list": ProvHelper.getTaskOutputs(task).collect { source -> [
                        "uri": normalizePath(source)
                    ] }
                ] },
            ],
            "execution_domain": [
                "script": [ normalizePath(metadata.scriptFile) ],
                "script_driver": "nextflow",
                "software_prerequisites": [
                    [
                        "name": "Nextflow",
                        "version": nextflowVersion,
                        "uri": [
                            "uri": "https://github.com/nextflow-io/nextflow/releases/tag/v${nextflowVersion}"
                        ]
                    ]
                ],
                "external_data_endpoints": [],
                "environment_variables": [:]
            ],
            "parametric_domain": params.toConfigObject().flatten().collect( (k, v) -> [
                "param": k,
                "value": normalizePath(v.toString()),
                "step": "0"
            ] ),
            "io_domain": [
                "input_subdomain": workflowInputs.collect { source -> [
                    "uri": [
                        "uri": normalizePath(source)
                    ]
                ] },
                "output_subdomain": workflowOutputs.collect { source, target -> [
                    "mediatype": Files.probeContentType(source) ?: "",
                    "uri": [
                        "filename": normalizePath(source),
                        "uri": normalizePath(target)
                    ]
                ] }
            ],
            "error_domain": [
                "empirical_error": [:],
                "algorithmic_error": [:]
            ]
        ]

        // append git repository info
        if( metadata.repository ) {
            final extension_domain = bco.extension_domain as List
            final scriptFile = metadata.scriptFile.toUriString()
            final projectDir = metadata.projectDir.toUriString()

            extension_domain << [
                "extension_schema": "https://w3id.org/biocompute/extension_domain/1.1.0/scm/scm_extension.json",
                "scm_extension": [
                    "scm_repository": metadata.repository,
                    "scm_type": "git",
                    "scm_commit": metadata.commitId,
                    "scm_path": scriptFile.replace(projectDir + '/', ''),
                    "scm_preview": normalizePath(metadata.scriptFile)
                ]
            ]
        }

        // compute etag
        // TODO: make a more canonical hash
        final etag = CacheHelper.hasher(bco, CacheHelper.HashMode.SHA256).hash()

        // append non-cacheable fields
        bco.object_id = "urn:uuid:${UUID.randomUUID()}"
        bco.spec_version = "https://w3id.org/ieee/ieee-2791-schema/2791object.json"
        bco.etag = etag.toString()

        // render BCO manifest to JSON file
        path.text = JsonOutput.prettyPrint(JsonOutput.toJson(bco))
    }

}
