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
import nextflow.exception.AbortOperationException
import nextflow.processor.TaskRun

/**
 * Renderer for the Provenance Run RO Crate format.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class WrrocRenderer implements Renderer {

    private Path path

    private boolean overwrite

    @Delegate
    private PathNormalizer normalizer

    WrrocRenderer(Map opts) {
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

        final formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        final dateStarted = formatter.format(metadata.start)
        final dateCompleted = formatter.format(metadata.complete)
        final nextflowVersion = nextflowMeta.version.toString()
        final params = session.config.params as Map

        // create manifest
        final softwareApplicationId = UUID.randomUUID()
        final organizeActionId = UUID.randomUUID()

        final authors = (manifest.author ?: '')
            .tokenize(',')
            .withIndex()
            .collect { String name, int i -> [
                "@id": "author-${i + 1}",
                "@type": "Person",
                "name": name.trim()
            ] }

        final formalParameters = params
            .toConfigObject()
            .flatten()
            .collect { name, value -> [
                "@id": "#${name}",
                "@type": "FormalParameter",
                // TODO: infer type from value at runtime
                // "additionalType": "File",
                // "defaultValue": "",
                "conformsTo": ["@id": "https://bioschemas.org/profiles/FormalParameter/1.0-RELEASE"],
                "description": "",
                // TODO: apply only if type is Path
                // "encodingFormat": "text/plain",
                // TODO: match to output if type is Path
                // "workExample": ["@id": outputId],
                "name": name,
                // "valueRequired": "True"
            ] }

        final inputFiles = workflowInputs
            .collect { source -> [
                "@id": normalizePath(source),
                "@type": "File",
                "description": "",
                "encodingFormat": Files.probeContentType(source) ?: "",
                // TODO: apply if matching param is found
                // "exampleOfWork": ["@id": paramId]
            ] }

        // TODO: create PropertyValue for each non-file FormalParameter output
        final propertyValues = [:]
            .collect { name, value -> [
                "@id": "#${name}",
                "@type": "PropertyValue",
                // TODO: match to param
                // "exampleOfWork": ["@id": "#verbose-param"],
                "name": name,
                "value": value
            ] }

        final outputFiles = workflowOutputs
            .collect { source, target -> [
                "@id": normalizePath(source),
                "@type": "File",
                "name": source.name,
                "description": "",
                "encodingFormat": Files.probeContentType(source) ?: "",
                // TODO: create FormalParameter for each output file?
                // "exampleOfWork": {"@id": "#reversed"}
            ] }

        final wrroc = [
            "@context": "https://w3id.org/ro/crate/1.1/context", 
            "@graph": [
                [
                    "@id": path.name,
                    "@type": "CreativeWork",
                    "about": ["@id": "./"],
                    "conformsTo": [
                        ["@id": "https://w3id.org/ro/crate/1.1"],
                        ["@id": "https://w3id.org/workflowhub/workflow-ro-crate/1.0"]
                    ]
                ],
                [
                    "@id": "./",
                    "@type": "Dataset",
                    "conformsTo": [
                        ["@id": "https://w3id.org/ro/wfrun/process/0.1"],
                        ["@id": "https://w3id.org/ro/wfrun/workflow/0.1"],
                        ["@id": "https://w3id.org/ro/wfrun/provenance/0.1"],
                        ["@id": "https://w3id.org/workflowhub/workflow-ro-crate/1.0"]
                    ],
                    "name": "Workflow run of ${metadata.projectName}",
                    "description": manifest.description ?: "",
                    "hasPart": [
                        ["@id": metadata.projectName],
                        *inputFiles.collect( file -> ["@id": file["@id"]] ),
                        *outputFiles.collect( file -> ["@id": file["@id"]] )
                    ],
                    "mainEntity": ["@id": metadata.projectName],
                    "mentions": ["@id": "#${session.uniqueId}"]
                ],
                [
                    "@id": "https://w3id.org/ro/wfrun/process/0.1",
                    "@type": "CreativeWork",
                    "name": "Process Run Crate",
                    "version": "0.1"
                ],
                [
                    "@id": "https://w3id.org/ro/wfrun/workflow/0.1",
                    "@type": "CreativeWork",
                    "name": "Workflow Run Crate",
                    "version": "0.1"
                ],
                [
                    "@id": "https://w3id.org/ro/wfrun/provenance/0.1",
                    "@type": "CreativeWork",
                    "name": "Provenance Run Crate",
                    "version": "0.1"
                ],
                [
                    "@id": "https://w3id.org/workflowhub/workflow-ro-crate/1.0",
                    "@type": "CreativeWork",
                    "name": "Workflow RO-Crate",
                    "version": "1.0"
                ],
                [
                    "@id": metadata.projectName,
                    "@type": ["File", "SoftwareSourceCode", "ComputationalWorkflow", "HowTo"],
                    "name": metadata.projectName,
                    "programmingLanguage": ["@id": "https://w3id.org/workflowhub/workflow-ro-crate#nextflow"],
                    "hasPart": [
                        // TODO: module files? processes?
                    ],
                    "input": formalParameters.collect( fp ->
                        ["@id": fp["@id"]]
                    ),
                    "output": [
                        // TODO: id of FormalParameter for each output file
                    ],
                    "step": [
                        // TODO: processes?
                    ]
                ],
                [
                    "@id": "https://w3id.org/workflowhub/workflow-ro-crate#nextflow",
                    "@type": "ComputerLanguage",
                    "name": "Nextflow",
                    "identifier": "https://www.nextflow.io/",
                    "url": "https://www.nextflow.io/",
                    "version": nextflowVersion
                ],
                // TODO: SoftwareApplication for each process w/ formal parameters
                *formalParameters,
                [
                    "@id": "#${softwareApplicationId}",
                    "@type": "SoftwareApplication",
                    "name": "Nextflow ${nextflowVersion}"
                ],
                [
                    "@id": "#${organizeActionId}",
                    "@type": "OrganizeAction",
                    "agent": authors ? ["@id": "author-1"] : null,
                    "instrument": ["@id": "#${softwareApplicationId}"],
                    "name": "Run of Nextflow ${nextflowVersion}",
                    "object": [
                        ["@id": "#4f7f887f-1b9b-4417-9beb-58618a125cc5"],
                        ["@id": "#793b3df4-cbb7-4d17-94d4-0edb18566ed3"]
                    ],
                    "result": ["@id": "#${session.uniqueId}"],
                    "startTime": dateStarted
                ],
                *authors,
                [
                    "@id": "#${session.uniqueId}",
                    "@type": "CreateAction",
                    "name": "Nextflow workflow run ${session.uniqueId}",
                    "startTime": dateStarted,
                    "endTime": dateCompleted,
                    "instrument": ["@id": metadata.projectName],
                    "object": [
                        *inputFiles.collect( file -> ["@id": file["@id"]] ),
                        *propertyValues.collect( pv -> ["@id", pv["@id"]] )
                    ],
                    "result": outputFiles.collect( file ->
                        ["@id": file["@id"]]
                    )
                ],
                *inputFiles,
                *propertyValues,
                *outputFiles
            ]
        ]

        // render manifest to JSON file
        path.text = JsonOutput.prettyPrint(JsonOutput.toJson(wrroc))
    }

}
