/*
 * Copyright 2022, Seqera Labs
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

import java.nio.file.Path
import java.time.format.DateTimeFormatter

import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import nextflow.Session
import nextflow.exception.AbortOperationException
import nextflow.processor.TaskRun
import nextflow.script.WorkflowMetadata
import nextflow.script.params.FileOutParam
import nextflow.util.CacheHelper

/**
 * Renderer for the BioCompute Object (BCO) format.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class BcoRenderer implements Renderer {

    private WorkflowMetadata metadata

    /**
     * Get the list of output files for a task.
     *
     * @param task
     */
    private static List<Path> getTaskOutputs(TaskRun task) {
        return task
            .getOutputsByType(FileOutParam)
            .values()
            .flatten() as List<Path>
    }

    /**
     * Get a mapping of output file to the task that produced it.
     *
     * @param tasks
     */
    private static Map<Path,TaskRun> getTaskLookup(Set<TaskRun> tasks) {
        final taskLookup = [:] as Map<Path,TaskRun>

        for( def task : tasks )
            for( def output : getTaskOutputs(task) )
                taskLookup[output] = task

        return taskLookup
    }

    /**
     * Get the list of workflow inputs. A workflow input is an input file
     * to a task that was not produced by another task.
     *
     * @param tasks
     * @param taskLookup
     */
    private static Set<Path> getWorkflowInputs(Set<TaskRun> tasks, Map<Path,TaskRun> taskLookup) {
        final workflowInputs = [] as Set<Path>

        tasks.each { task ->
            task.getInputFilesMap().each { name, path ->
                if( taskLookup[path] )
                    return

                workflowInputs << path
            }
        }

        return workflowInputs
    }

    /**
     * Normalize local paths to remove environment-specific directories.
     *
     * @param path
     */
    private String normalizePath(Path path) {
        // TODO: append raw github URL to git assets
        path.toUriString()
            .replace(metadata.workDir.toUriString(), '/work')
            .replace(metadata.projectDir.toUriString(), '')
            .replace(metadata.launchDir.toUriString(), '')
    }

    @Override
    void render(Session session, Set<TaskRun> tasks, Map<Path,Path> workflowOutputs, Path path) {
        if( !path.mkdirs() )
            throw new AbortOperationException("Unable to create directory for provenance manifest: ${path.toUriString()}")

        // get workflow inputs
        final taskLookup = getTaskLookup(tasks)
        final workflowInputs = getWorkflowInputs(tasks, taskLookup)

        // get workflow metadata
        this.metadata = session.workflowMetadata
        final manifest = metadata.manifest
        final manifestExtra = session.config.navigate('prov.metadata', [:]) as Map

        final formatter = DateTimeFormatter.ISO_INSTANT
        final dateCreated = formatter.format(metadata.start)
        final authors = (manifest.author ?: '').tokenize(',')

        // render BCO manifest
        final bcoPath = path.resolve('bco.json')
        final bco = [
            "object_id": null,
            "spec_version": null,
            "etag": null,
            "provenance_domain": [
                "name": manifest.name,
                "version": manifest.version,
                "created": dateCreated,
                "modified": dateCreated,
                "contributors": authors.collect( name -> [
                    "contribution": ["authoredBy"],
                    "name": name
                ] ),
                "license": manifestExtra.license
            ],
            "description_domain": [
                "pipeline_steps": tasks.collect { task -> [
                    "step_number": task.id,
                    "name": task.hash.toString(),
                    "description": task.name,
                    "input_list": task.getInputFilesMap().collect { name, source -> 
                        normalizePath(source)
                    },
                    "output_list": getTaskOutputs(task).collect { source ->
                        normalizePath(source)
                    }
                ] },
            ],
            "execution_domain": [
                "script": [ normalizePath(metadata.scriptFile) ],
                "script_driver": "nextflow"
            ],
            "io_domain": [
                "input_subdomain": workflowInputs.collect { source ->
                    [ "uri": normalizePath(source) ]
                },
                "output_subdomain": workflowOutputs.collect { source, target ->
                    [ "uri": normalizePath(target), "filename": normalizePath(source) ]
                }
            ]
        ]

        // compute etag
        // TODO: make a more canonical hash
        final etag = CacheHelper.hasher(bco, CacheHelper.HashMode.SHA256).hash()

        // append non-cacheable fields
        bco.object_id = "urn:uuid:${UUID.randomUUID()}"
        bco.spec_version = "https://w3id.org/ieee/ieee-2791-schema/"
        bco.etag = etag.toString()

        // save BCO manifest to JSON file
        bcoPath.text = JsonOutput.prettyPrint(JsonOutput.toJson(bco))

        // render RO-Crate manifest
        final rocPath = path.resolve('ro-crate-metadata.json')
        final roCrate = [
            "@context": "https://w3id.org/ro/crate/1.1/context", 
            "@graph": [
                [
                    "@id": "",
                    "@type": "CreativeWork",
                    "conformsTo": ["@id": "https://w3id.org/ro/crate/1.1"],
                    "about": ["@id": "./"]
                ],
                [
                    "@id": "./",
                    "@type": "Dataset",
                    "name": "Workflow run of ${metadata.projectName}",
                    "description": "${manifest.description}",
                    "author": [
                        "@id": "https://orcid.org/0000-0001-9842-9718"
                    ],
                    "datePublished": "${dateCreated}",
                    "distribution": [
                        "@id": "https://github.com/stain/bco-ro-example-chipseq/archive/master.zip"
                    ],
                    "hasPart": [
                        [
                            "@id": "${bcoPath.name}"
                        ]
                    ],
                    "license": [
                        "@id": "https://spdx.org/licenses/CC0-1.0"
                    ]
                ],
                [
                    "@id": "${bcoPath.name}",
                    "@type": "File",
                    "identifier": "${bco.object_id}",
                    "name": "${bcoPath.name}",
                    "description": "IEEE 2791 description (BioCompute Object) of ${metadata.projectName}",
                    "encodingFormat": [
                        "application/json", 
                        ["@id": "https://www.nationalarchives.gov.uk/PRONOM/fmt/817"]
                    ],    
                    "conformsTo": [
                        "@id": "https://w3id.org/ieee/ieee-2791-schema/"
                    ]
                ],
                [
                    "@id": "https://w3id.org/ieee/ieee-2791-schema/",
                    "@type": "CreativeWork",
                    "name": "IEEE 2791 Object Schema",
                    "version": "1.4",
                    "citation": "https://doi.org/10.1109/IEEESTD.2020.9094416",
                    "subjectOf": ["@id": "https://www.biocomputeobject.org/"]
                ]
            ]
        ]

        // save RO-Crate manifest to JSON file
        rocPath.text = JsonOutput.prettyPrint(JsonOutput.toJson(roCrate))
    }

}
