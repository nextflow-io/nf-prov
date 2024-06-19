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

import nextflow.script.params.FileOutParam

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.format.DateTimeFormatter

import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import nextflow.Session
import nextflow.processor.TaskRun

/**
 * Renderer for the Provenance Run RO Crate format.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 * @author Felix Bartusch <felix.bartusch@uni-tuebingen.de>
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
        final scriptFile = metadata.getScriptFile()

        final formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        final dateStarted = formatter.format(metadata.start)
        final dateCompleted = formatter.format(metadata.complete)
        final nextflowVersion = nextflowMeta.version.toString()
        final params = session.config.params as Map
        final wrrocParams = session.config.prov["formats"]["wrroc"] as Map

        // get RO-Crate Root
        final crateRootDir = Path.of(params['outdir'].toString()).toAbsolutePath()

        // Copy workflow into crate directory
        Files.copy(scriptFile, crateRootDir.resolve(scriptFile.getFileName()), StandardCopyOption.REPLACE_EXISTING)

        // create manifest
        final softwareApplicationId = UUID.randomUUID()
        final organizeActionId = UUID.randomUUID()

        final LinkedHashMap agent = new LinkedHashMap()
        if (wrrocParams.containsKey("agent")) {
            Map agentMap = wrrocParams["agent"] as Map
            if(agentMap.containsKey("orcid")) {
                agent.put("@id", agentMap.get("orcid"))
            } else {
                agent.put("@id", "agent-1")
            }
            agent.put("@type", "Person")
            if(agentMap.containsKey("name")) {
                agent.put("name", agentMap.get("name"))
            }
        }

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
                "@id": crateRootDir.relativize(target).toString(),
                "@type": "File",
                "name": target.name,
                "description": "",
                "encodingFormat": Files.probeContentType(target) ?: "",
                // TODO: create FormalParameter for each output file?
                // "exampleOfWork": {"@id": "#reversed"}
            ] }

        // Maps used for finding tasks/CreateActions corresponding to a Nextflow process
        Map <String, List> processToTasks = [:].withDefault { [] }

        def createActions = tasks
            .collect { task ->
                List<String> resultFileIDs = []
                for (taskOutputParam in task.getOutputsByType(FileOutParam)) {
                    for (taskOutputFile in taskOutputParam.getValue()) {
                        resultFileIDs.add(crateRootDir.relativize(workflowOutputs.get(Path.of(taskOutputFile.toString()))).toString())
                    }
                }

                def createAction = [
                    "@id": "#" + task.getHash().toString(),
                    "@type": "CreateAction",
                    "name": task.getName(),
                    // TODO: There is no description for Nextflow processes?
                    //"description" : "",
                    // TODO: task doesn't contain startTime information. TaskHandler does, but is not available to WrrocRenderer
                    //"startTime": "".
                    // TODO: Same as for startTime
                    //"endTime": "",
                    "instrument": ["@id": "#" + task.getProcessor().ownerScript.toString()],
                    "agent": ["@id" : agent.get("@id").toString()],
                    // TODO: Add input file references.
                    //"object": task.getInputFilesMap() ???
                    "result": [
                        resultFileIDs.collect( file -> ["@id": file] )
                    ],
                    "actionStatus": task.getExitStatus()==0 ? "CompletedActionStatus" : "FailedActionStatus"
                ]

                // Add error message if there is one
                if (task.getExitStatus()!=0) {
                    createAction.put("error", task.getStderr())
                }

                return createAction
            }

        final nextflowProcesses = tasks
            .collect { task ->
                processToTasks[task.getProcessor().getId().toString()].add("#${task.getHash().toString()}")
                return task.getProcessor()
            }.unique()

        final wfSofwareApplications = nextflowProcesses
            .collect() { process -> [
                "@id": "#" + process.ownerScript.toString(),
                "@type": "SoftwareApplication",
                "name": process.getName()
            ] }

        final howToSteps = nextflowProcesses
            .collect() { process -> [
                "@id": metadata.projectName + "#main/" + process.getName(),
                "@type": "HowToStep",
                "workExample":  ["@id": "#" + process.ownerScript.toString()],
                "position": process.getId()
            ] }

        final controlActions = nextflowProcesses
            .collect() { process -> [
                "@id": UUID.randomUUID(),
                "@type": "ControlAction",
                "instrument":  ["@id": "${metadata.projectName}#main/${process.getName()}"],
                "name": "orchestrate " + "${metadata.projectName}#main/${process.getName()}",
                "object": processToTasks[process.getId().toString()].collect( { taskID ->
                    ["@id": taskID]
                } )
                // TODO: add actionStatus and error? But it's already implemented for createAction.
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
                    "mentions": [
                        ["@id": "#${session.uniqueId}"],
                        *createActions.collect( createAction -> ["@id": createAction["@id"]] )
                    ],
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
                    "hasPart": wfSofwareApplications.collect( sa ->
                        ["@id": sa["@id"]]
                    ),
                    "input": formalParameters.collect( fp ->
                        ["@id": fp["@id"]]
                    ),
                    "output": [
                        // TODO: id of FormalParameter for each output file
                    ],
                    "step": howToSteps.collect( step ->
                        ["@id": step["@id"]]
                    ),
                ],
                [
                    "@id": "https://w3id.org/workflowhub/workflow-ro-crate#nextflow",
                    "@type": "ComputerLanguage",
                    "name": "Nextflow",
                    "identifier": "https://www.nextflow.io/",
                    "url": "https://www.nextflow.io/",
                    "version": nextflowVersion
                ],
                *wfSofwareApplications,
                *formalParameters,
                [
                    "@id": "#${softwareApplicationId}",
                    "@type": "SoftwareApplication",
                    "name": "Nextflow ${nextflowVersion}"
                ],

                *howToSteps,
                [
                    "@id": "#${organizeActionId}",
                    "@type": "OrganizeAction",
                    "agent":  ["@id" : agent.get("@id").toString()],
                    "instrument": ["@id": "#${softwareApplicationId}"],
                    "name": "Run of Nextflow ${nextflowVersion}",
                    "object": [
                        *controlActions.collect( action -> ["@id": action["@id"]] )
                    ],
                    "result": ["@id": "#${session.uniqueId}"],
                    "startTime": dateStarted,
                    "endTime": dateCompleted
                ],
                [
                    "id": "#${session.uniqueId}",
                    "@type": "CreateAction",
                    "agent":  ["@id" : agent.get("@id").toString()],
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
                *[agent],
                *controlActions,
                *createActions,
                *inputFiles,
                *propertyValues,
                *outputFiles
            ]
        ]

        // render manifest to JSON file
        path.text = JsonOutput.prettyPrint(JsonOutput.toJson(wrroc))
    }

}
