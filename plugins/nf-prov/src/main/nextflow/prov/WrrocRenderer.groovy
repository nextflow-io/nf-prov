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

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.processor.TaskProcessor
import nextflow.processor.TaskRun
import nextflow.script.ProcessDef
import nextflow.script.ScriptMeta
import nextflow.util.ConfigHelper
import org.yaml.snakeyaml.Yaml

/**
 * Renderer for the Provenance Run RO Crate format.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 * @author Felix Bartusch <felix.bartusch@uni-tuebingen.de>
 * @author Famke Bäuerle <famke.baeuerle@qbic.uni-tuebingen.de>
 */
@Slf4j
@CompileStatic
class WrrocRenderer implements Renderer {

    private static final List<String> README_FILENAMES = List.of("README.md", "README.txt", "readme.md", "readme.txt", "Readme.md", "Readme.txt", "README")

    private Path path

    private boolean overwrite

    @Delegate
    private PathNormalizer normalizer

    // List of contact points (people, organizations) to be added
    private List<Map> contactPoints = []

    WrrocRenderer(Map opts) {
        path = (opts.file as Path).complete()
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
        final crateDir = path.getParent()
        final projectDir = metadata.projectDir
        this.normalizer = new PathNormalizer(metadata)

        final manifest = metadata.manifest
        final scriptFile = metadata.getScriptFile()

        final formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        final dateStarted = formatter.format(metadata.start)
        final dateCompleted = formatter.format(metadata.complete)
        final nextflowVersion = metadata.nextflow.version.toString()
        final params = session.params

        // parse wrroc configuration
        final wrrocOpts = session.config.navigate('prov.formats.wrroc', [:]) as Map
        final agent = getAgentInfo(wrrocOpts)
        final organization = getOrganizationInfo(wrrocOpts)
        final publisherId = getPublisherId(wrrocOpts, agent, organization)
        if( organization )
            agent["affiliation"] = ["@id": organization["@id"]]

        // create manifest
        final datasetParts = []

        // -- license
        if( manifest.license ) {
            datasetParts.add([
                "@id"  : manifest.license,
                "@type": "CreativeWork"
            ])
        }

        // -- readme file
        for( final fileName : README_FILENAMES ) {
            final readmePath = projectDir.resolve(fileName)
            if( !Files.exists(readmePath) )
                continue

            readmePath.copyTo(crateDir)
            datasetParts.add([
                "@id"           : fileName,
                "@type"         : "File",
                "name"          : fileName,
                "description"   : "The README file of the workflow.",
                "encodingFormat": getEncodingFormat(readmePath) ?: "text/plain"
            ])
            break
        }

        // -- main script
        final mainScriptId = metadata.scriptFile.name
        final softwareApplicationId = "${mainScriptId}#software-application"
        final organizeActionId = "${mainScriptId}#organize"
        metadata.scriptFile.copyTo(crateDir)

        if( !metadata.repository )
            log.warn "Could not determine pipeline repository URL for Workflow Run RO-Crate -- launch the pipeline with canonical URL (e.g. `nextflow run nextflow-io/hello`) to ensure that the pipeline repository URL is recorded in the crate"

        // -- parameter schema
        final schemaPath = scriptFile.getParent().resolve("nextflow_schema.json")
        Map<String,Map> paramSchema = [:]
        if( Files.exists(schemaPath) ) {
            final fileName = schemaPath.name

            schemaPath.copyTo(crateDir)
            datasetParts.add([
                "@id"           : fileName,
                "@type"         : "File",
                "name"          : fileName,
                "description"   : "The parameter schema of the workflow.",
                "encodingFormat": "application/json"
            ])
            paramSchema = getParameterSchema(schemaPath)
        }

        // -- resolved config
        final configPath = crateDir.resolve("nextflow.config")
        configPath.text = ConfigHelper.toCanonicalString(session.config, true)

        datasetParts.add([
            "@id"           : "nextflow.config",
            "@type"         : "File",
            "name"          : "Resolved Nextflow configuration",
            "description"   : "The resolved Nextflow configuration for the workflow run.",
            "encodingFormat": "text/plain"
        ])

        // -- pipeline parameters
        // TODO: formal parameters for workflow output targets
        final formalParameters = params
            .collect { name, value ->
                final schema = paramSchema[name] ?: [:]
                final type = getParameterType(name, value, schema)
                final encoding = type == "File"
                    ? getEncodingFormat(value as Path)
                    : null

                if( !type )
                    log.warn "Could not determine type of parameter `${name}` for Workflow Run RO-Crate"

                return withoutNulls([
                    "@id"           : getFormalParameterId(name),
                    "@type"         : "FormalParameter",
                    "additionalType": type,
                    "conformsTo"    : ["@id": "https://bioschemas.org/profiles/FormalParameter/1.0-RELEASE"],
                    "encodingFormat": encoding,
                    "name"          : name,
                    "defaultValue"  : schema.default,
                    "description"   : schema.description,
                ])
            }

        final propertyValues = params
            .findAll { name, value -> value != null }
            .collect { name, value ->
                final paramId = getFormalParameterId(name)
                final normalized =
                    (value instanceof List || value instanceof Map) ? JsonOutput.toJson(value)
                    : value instanceof CharSequence ? normalizePath(value.toString())
                    : value

                return [
                    "@id"          : "${paramId}/value",
                    "@type"        : "PropertyValue",
                    "exampleOfWork": ["@id": paramId],
                    "name"         : name,
                    "value"        : normalized
                ]
            }

        // -- input files
        final inputFiles = workflowInputs
            .findAll { source -> !ProvHelper.isStagedInput(source, session) }
            .collect { source ->
                withoutNulls([
                    "@id"           : normalizePath(source),
                    "@type"         : getType(source),
                    "name"          : source.name,
                    "encodingFormat": getEncodingFormat(source),
                ])
            }

        // -- copy input files from params to crate
        params.each { name, value ->
            if( !value )
                return
            final schema = paramSchema[name] ?: [:]
            final type = getParameterType(name, value, schema)
            if( type == "File" ) {
                final source = (value as Path).complete()
                // don't try to download remote files
                if( source.fileSystem != FileSystems.default )
                    return
                // don't try to copy local directories
                if( !source.isFile() )
                    return
                inputFiles.add(withoutNulls([
                    "@id"           : source.name,
                    "@type"         : type,
                    "description"   : "Input file specified by params.${name}",
                    "encodingFormat": getEncodingFormat(source)
                ]))
                log.debug "Copying input file specified by params.${name} into RO-Crate: ${source.toUriString()}"
                source.copyTo(crateDir)
            }
        }

        // -- output files
        final outputFiles = workflowOutputs
            .findAll { source, target ->
                // warn about any output files outside of the crate directory
                final result = target.startsWith(crateDir)
                if( !result )
                    log.warn "Excluding workflow output ${target} because it is outside of the RO-Crate directory -- make sure that the workflow output directory and RO-Crate directory are the same"
                return result
            }
            .collect { source, target ->
                withoutNulls([
                    "@id"           : crateDir.relativize(target).toString(),
                    "@type"         : getType(target),
                    "name"          : target.name,
                    "encodingFormat": getEncodingFormat(target),
                ])
            }

        // -- workflow definition
        final taskProcessors = tasks
            .collect { task -> task.processor }
            .unique()

        final processDefs = taskProcessors
            .collect { process -> ScriptMeta.get(process.getOwnerScript()) }
            .unique()
            .collectMany { meta ->
                meta.getDefinitions().findAll { defn -> defn instanceof ProcessDef } as List<ProcessDef>
            }

        final processLookup = taskProcessors
            .inject([:] as Map<TaskProcessor,ProcessDef>) { acc, processor ->
                final simpleName = processor.name.split(':').last()
                acc[processor] = ScriptMeta.get(processor.getOwnerScript()).getProcess(simpleName)
                acc
            }

        final moduleSoftwareApplications = processDefs
            .collect() { process ->
                final result = [
                    "@id"    : getModuleId(process),
                    "@type"  : "SoftwareApplication",
                    "name"   : process.baseName,
                    "url"    : getModuleUrl(process),
                ]

                final metaYaml = getModuleSchema(process)
                if( metaYaml ) {
                    final name = metaYaml.name as String
                    final tools = metaYaml.getOrDefault('tools', []) as List
                    final parts = tools.collect { tool ->
                        final entry = (tool as Map).entrySet().first()
                        final toolName = entry.key as String
                        ["@id": getToolId(process.baseName, toolName)]
                    }

                    if( name )
                        result.name = name
                    if( parts )
                        result.hasPart = parts
                }

                return result
            }

        final toolSoftwareApplications = processDefs
            .collectMany { process ->
                final metaYaml = getModuleSchema(process)
                if( !metaYaml )
                    return []

                final tools = metaYaml.getOrDefault('tools', []) as List
                return tools
                    .collect { tool ->
                        final entry = (tool as Map).entrySet().first()
                        final toolName = entry.key as String
                        final toolDescription = (entry.value as Map)?.get('description') as String
                        return [
                            "@id"         : getToolId(process.baseName, toolName),
                            "@type"       : "SoftwareApplication",
                            "name"        : toolName,
                            "description" : toolDescription
                        ]
                    }
            }

        final howToSteps = taskProcessors
            .collect() { process ->
                [
                    "@id"        : getProcessStepId(process),
                    "@type"      : "HowToStep",
                    "workExample": ["@id": getModuleId(processLookup[process])],
                    "position"   : process.getId()
                ]
            }

        final controlActions = taskProcessors
            .collect() { process ->
                final taskIds = tasks
                    .findAll { task -> task.processor == process }
                    .collect { task -> ["@id": getTaskId(task)] }

                return [
                    "@id"       : getProcessControlId(process),
                    "@type"     : "ControlAction",
                    "instrument": ["@id": getProcessStepId(process)],
                    "name"      : "Orchestrate process ${process.name}",
                    "object"    : taskIds
                ]
            }

        // -- workflow execution
        final stagedInputs = workflowInputs
            .findAll { source -> ProvHelper.isStagedInput(source, session) }
            .collect { source ->
                final name = getStagedInputName(source, session)

                withoutNulls([
                    "@id"           : "#stage/${name}",
                    "@type"         : "CreativeWork",
                    "name"          : name,
                    "encodingFormat": getEncodingFormat(source),
                ])
            }

        final taskCreateActions = tasks
            .collect { task ->
                final inputs = task.getInputFilesMap().collect { name, source ->
                    final id =
                        source in taskLookup ? getTaskOutputId(taskLookup[source], source)
                        : ProvHelper.isStagedInput(source, session) ? "#stage/${getStagedInputName(source, session)}"
                        : normalizePath(source)
                    ["@id": id]
                }
                final outputs = ProvHelper.getTaskOutputs(task).collect { target ->
                    ["@id": getTaskOutputId(task, target)]
                }
                final result = [
                    "@id"         : getTaskId(task),
                    "@type"       : "CreateAction",
                    "name"        : task.name,
                    "instrument"  : ["@id": getModuleId(processLookup[task.processor])],
                    "agent"       : agent ? ["@id": agent["@id"]] : null,
                    "object"      : inputs,
                    "result"      : outputs,
                    "actionStatus": task.exitStatus == 0 ? "http://schema.org/CompletedActionStatus" : "http://schema.org/FailedActionStatus"
                ]
                if( task.exitStatus != 0 )
                    result["error"] = task.stderr
                return result
            }

        final taskOutputs = tasks.collectMany { task ->
            ProvHelper.getTaskOutputs(task).collect { target ->
                final name = getTaskOutputName(task, target)

                return withoutNulls([
                    "@id"           : getTaskOutputId(task, name),
                    "@type"         : "CreativeWork",
                    "name"          : name,
                    "encodingFormat": getEncodingFormat(target),
                ])
            }
        }

        final publishCreateActions = workflowOutputs
            .collect { source, target ->
                final task = taskLookup[source]
                final sourceName = getTaskOutputName(task, source)

                return [
                    "@id"         : "#publish/${task.hash}/${sourceName}",
                    "@type"       : "CreateAction",
                    "name"        : "publish",
                    "instrument"  : ["@id": softwareApplicationId],
                    "object"      : ["@id": getTaskOutputId(task, sourceName)],
                    "result"      : ["@id": crateDir.relativize(target).toString()],
                    "actionStatus": "http://schema.org/CompletedActionStatus"
                ]
            }

        final wrroc = [
            "@context": "https://w3id.org/ro/crate/1.1/context",
            "@graph"  : withoutNulls([
                [
                    "@id"       : path.name,
                    "@type"     : "CreativeWork",
                    "about"     : ["@id": "./"],
                    "conformsTo": [
                        ["@id": "https://w3id.org/ro/crate/1.1"],
                        ["@id": "https://w3id.org/workflowhub/workflow-ro-crate/1.0"]
                    ]
                ],
                withoutNulls([
                    "@id"        : "./",
                    "@type"      : "Dataset",
                    "author"     : agent ? ["@id": agent["@id"]] : null,
                    "publisher"  : publisherId ? ["@id": publisherId] : null,
                    "datePublished": getDatePublished(),
                    "conformsTo" : [
                        ["@id": "https://w3id.org/ro/wfrun/process/0.1"],
                        ["@id": "https://w3id.org/ro/wfrun/workflow/0.1"],
                        ["@id": "https://w3id.org/ro/wfrun/provenance/0.1"],
                        ["@id": "https://w3id.org/workflowhub/workflow-ro-crate/1.0"]
                    ],
                    "name"       : "Workflow run of ${manifest.name ?: metadata.projectName}",
                    "description": manifest.description ?: null,
                    "hasPart"    : withoutNulls([
                        ["@id": mainScriptId],
                        *asReferences(datasetParts),
                        *asReferences(inputFiles),
                        *asReferences(outputFiles)
                    ]),
                    "mainEntity" : ["@id": mainScriptId],
                    "mentions"   : [
                        ["@id": "#${session.uniqueId}"],
                        *asReferences(stagedInputs),
                        *asReferences(taskCreateActions),
                        *asReferences(taskOutputs),
                        *asReferences(publishCreateActions),
                    ],
                    "license"    : manifest.license
                ]),
                [
                    "@id"    : "https://w3id.org/ro/wfrun/process/0.1",
                    "@type"  : "CreativeWork",
                    "name"   : "Process Run Crate",
                    "version": "0.1"
                ],
                [
                    "@id"    : "https://w3id.org/ro/wfrun/workflow/0.1",
                    "@type"  : "CreativeWork",
                    "name"   : "Workflow Run Crate",
                    "version": "0.1"
                ],
                [
                    "@id"    : "https://w3id.org/ro/wfrun/provenance/0.1",
                    "@type"  : "CreativeWork",
                    "name"   : "Provenance Run Crate",
                    "version": "0.1"
                ],
                [
                    "@id"    : "https://w3id.org/workflowhub/workflow-ro-crate/1.0",
                    "@type"  : "CreativeWork",
                    "name"   : "Workflow RO-Crate",
                    "version": "1.0"
                ],
                withoutNulls([
                    "@id"                : mainScriptId,
                    "@type"              : ["File", "SoftwareSourceCode", "ComputationalWorkflow", "HowTo"],
                    "conformsTo"         : ["@id": "https://bioschemas.org/profiles/ComputationalWorkflow/1.0-RELEASE"],
                    "name"               : manifest.name ?: metadata.projectName,
                    "description"        : manifest.description,
                    "programmingLanguage": ["@id": "https://w3id.org/workflowhub/workflow-ro-crate#nextflow"],
                    "creator"            : manifest.author,
                    "codeRepository"     : metadata.repository,
                    "version"            : metadata.commitId,
                    "license"            : manifest.license,
                    "url"                : metadata.repository ? normalizePath(metadata.scriptFile) : null,
                    "encodingFormat"     : "application/nextflow",
                    "runtimePlatform"    : "Nextflow " + nextflowVersion,
                    "hasPart"            : asReferences(moduleSoftwareApplications),
                    "input"              : asReferences(formalParameters),
                    "output"             : [
                        // TODO: workflow output targets
                    ],
                    "step"               : asReferences(howToSteps),
                ]),
                [
                    "@id"       : "https://w3id.org/workflowhub/workflow-ro-crate#nextflow",
                    "@type"     : "ComputerLanguage",
                    "name"      : "Nextflow",
                    "identifier": "https://www.nextflow.io/",
                    "url"       : "https://www.nextflow.io/",
                    "version"   : nextflowVersion
                ],
                [
                    "@id"  : softwareApplicationId,
                    "@type": "SoftwareApplication",
                    "name" : "Nextflow ${nextflowVersion}"
                ],
                *moduleSoftwareApplications,
                *toolSoftwareApplications,
                *formalParameters,
                *howToSteps,
                [
                    "@id"       : organizeActionId,
                    "@type"     : "OrganizeAction",
                    "agent"     : agent ? ["@id": agent["@id"]] : null,
                    "instrument": ["@id": softwareApplicationId],
                    "name"      : "Run of Nextflow ${nextflowVersion}",
                    "object"    : asReferences(controlActions),
                    "result"    : ["@id": "#${session.uniqueId}"],
                    "startTime" : dateStarted,
                    "endTime"   : dateCompleted
                ],
                [
                    "@id"       : "#${session.uniqueId}",
                    "@type"     : "CreateAction",
                    "agent"     : agent ? ["@id": agent["@id"]] : null,
                    "name"      : "Nextflow workflow run ${session.uniqueId}",
                    "startTime" : dateStarted,
                    "endTime"   : dateCompleted,
                    "instrument": ["@id": mainScriptId],
                    "object"    : [
                        *asReferences(propertyValues),
                        *asReferences(inputFiles),
                    ],
                    "result"    : asReferences(outputFiles)
                ],
                agent,
                organization,
                *contactPoints,
                *datasetParts,
                *propertyValues,
                *controlActions,
                *stagedInputs,
                *taskCreateActions,
                *taskOutputs,
                *publishCreateActions,
                *inputFiles,
                *outputFiles,
            ])
        ]

        // render manifest to JSON file
        path.text = JsonOutput.prettyPrint(JsonOutput.toJson(wrroc))
    }

    private static String getDatePublished() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_DATE)
    }

    /**
     * Parse information about the agent running the workflow.
     *
     * @param opts
     */
    private Map getAgentInfo(Map opts) {
        final result = [:]

        if( !opts.agent )
            return null

        final agentOpts = opts.agent as Map
        result["@id"] = agentOpts.getOrDefault("orcid", "agent-1")
        result["@type"] = "Person"
        if( agentOpts.name )
            result.name = agentOpts.name

        // Check for contact information
        if( agentOpts.email || agentOpts.phone ) {
            final contactPointId = getContactPointInfo(agentOpts)
            if( contactPointId )
                result.contactPoint = ["@id": contactPointId]
        }

        return result
    }

    /**
     * Parse information about the organization of the agent running the workflow.
     *
     * @param opts
     */
    private Map getOrganizationInfo(Map opts) {
        final result = [:]

        if( !opts.organization )
            return null

        final orgOpts = opts.organization as Map
        result["@id"] = orgOpts.getOrDefault("ror", "organization-1")
        result["@type"] = "Organization"
        if( orgOpts.name )
            result.name = orgOpts.name

        // Check for contact information
        if( orgOpts.email || orgOpts.phone ) {
            final contactPointId = getContactPointInfo(orgOpts)
            if( contactPointId )
                result.contactPoint = ["@id": contactPointId]
        }

        return result
    }

    /**
     * Parse a contact point and add it to the list of contact points.
     *
     * @param opts
     */
    private String getContactPointInfo(Map opts) {
        // Prefer email for the contact point ID
        String contactPointId = null
        if( opts.email )
            contactPointId = "mailto:" + opts.email
        else if( opts.phone )
            contactPointId = opts.phone

        if( !contactPointId )
            return null

        final contactPoint = [:]
        contactPoint["@id"] = contactPointId
        contactPoint["@type"] = "ContactPoint"
        if( opts.contactType )
            contactPoint.contactType = opts.contactType
        if( opts.email )
            contactPoint.email = opts.email
        if( opts.phone )
            contactPoint.phone = opts.phone
        if( opts.orcid )
            contactPoint.url = opts.orcid
        if( opts.ror )
            contactPoint.url = opts.ror

        contactPoints.add(contactPoint)
        return contactPointId
    }

    /**
     * Parse information about the RO-Crate publisher.
     *
     * @param opts
     * @param agent
     * @param organization
     */
    private static String getPublisherId(Map opts, Map agent, Map organization) {
        if( !opts.publisher )
            return null

        final publisherId = opts.publisher

        // Check if the publisher id references either the agent or the organization
        final agentId = agent?["@id"]
        final organizationId = organization?["@id"]
        if( publisherId != agentId && publisherId != organizationId )
            return null

        return publisherId
    }

    /**
     * Get the parameter schema of a pipeline as a map.
     *
     * @param path
     */
    private static Map<String,Map> getParameterSchema(Path path) {
        final schema = new JsonSlurper().parseText(path.text) as Map

        Map defs = null
        if( schema['$defs'] )
            defs = schema['$defs'] as Map
        else if( schema['defs'] )
            defs = schema['defs'] as Map
        else if( schema['definitions'] )
            defs = schema['definitions'] as Map

        if( !defs )
            return [:]

        final schemaProps = schema.properties as Map ?: [:]
        final defsProps = defs.values().collect { defn ->
            (defn as Map).properties ?: [:]
        } as List<Map>
        final allProps = [schemaProps] + defsProps
        final entries = allProps.collectMany { props ->
            (props as Map).entrySet()
        } as Map.Entry<String,Map>[]

        return Map.ofEntries(entries)
    }

    /**
     * Determine the type of a parameter based on its
     * schema and/or runtime value.
     *
     * @param name
     * @param value
     * @param schema
     */
    private static String getParameterType(String name, Object value, Map schema) {
        // infer from schema
        if( schema ) {
            final type = schema.type
            final format = schema.format

            switch( type ) {
                case "boolean":
                    return "Boolean"
                case "integer":
                case "number":
                    return "Number"
                case "string":
                    return \
                        format == "file-path" ? "File" :
                        format == "directory-path" ? "Dataset" :
                        "Text"
            }
        }

        // infer from runtime value
        switch( value ) {
            case Boolean:
                return "Boolean"
            case Number:
                return "Number"
            case CharSequence:
                return "Text"
            case List:
            case Map:
                return "Text"
            default:
                return null
        }
    }

    /**
     * Get the canonical id of a module script.
     *
     * @param name
     */
    private String getFormalParameterId(String name) {
        return "#param/${name}"
    }

    /**
     * Get the canonical id of a module script.
     *
     * @param process
     */
    private String getModuleId(ProcessDef process) {
        return "#module/${process.baseName}"
    }

    /**
     * Get the canonical url of a module script.
     *
     * @param process
     */
    private String getModuleUrl(ProcessDef process) {
        final scriptPath = ScriptMeta.get(process.getOwner()).getScriptPath().normalize()
        return normalizePath(scriptPath)
    }

    /**
     * Get the canonical id of a tool used by a module.
     *
     * @param moduleName
     * @param toolName
     */
    private static String getToolId(String moduleName, String toolName) {
        return "#module/${moduleName}/${toolName}"
    }

    /**
     * Get the canonical id of a process in the workflow DAG.
     *
     * @param process
     */
    private static String getProcessControlId(TaskProcessor process) {
        return "#process-control/${process.name}"
    }

    private static String getProcessStepId(TaskProcessor process) {
        return "#process-step/${process.name}"
    }

    /**
     * Get the relative name of a staged input.
     *
     * @param source
     * @param session
     */
    private static String getStagedInputName(Path source, Session session) {
        final stageDir = ProvHelper.getStageDir(session)
        return stageDir.relativize(source).toString()
    }

    /**
     * Get the canonical id of a task.
     *
     * @param task
     */
    private static String getTaskId(TaskRun task) {
        return "#task/${task.hash}"
    }

    /**
     * Get the relative name of a task output.
     *
     * @param task
     * @param target
     */
    private static String getTaskOutputName(TaskRun task, Path target) {
        final workDir = task.workDir.toUriString()
        return target.toUriString().replace(workDir + '/', '')
    }

    /**
     * Get the canonical id of a task output.
     *
     * @param task
     * @param name
     */
    private static String getTaskOutputId(TaskRun task, String name) {
        return "#task/${task.hash}/${name}"
    }

    private static String getTaskOutputId(TaskRun task, Path target) {
        return "#task/${task.hash}/${getTaskOutputName(task, target)}"
    }

    /**
     * Get the nf-core meta.yml of a Nextflow module as a map.
     *
     * @param process
     */
    private static Map getModuleSchema(ProcessDef process) {
        final metaFile = ScriptMeta.get(process.getOwner()).getModuleDir().resolve('meta.yml')
        return Files.exists(metaFile)
            ? new Yaml().load(metaFile.text) as Map
            : null
    }

    /**
     * Get the RO-Crate "@type" of a path based on whether
     * it is a file or directory.
     *
     * @param path
     */
    private static String getType(Path path) {
        return path.isDirectory()
            ? "Dataset"
            : "File"
    }

    /**
     * Get the encodingFormat of a file as MIME Type.
     *
     * @param path Path to file
     * @return the MIME type of the file, or null if it's not a file.
     */
    private static String getEncodingFormat(Path path) {
        if( !(path && path.exists() && path.isFile()) )
            return null

        String mime = Files.probeContentType(path)
        if( mime )
            return mime

        // It seems that YAML has a media type only since beginning of 2024
        // Set this by hand if this is run on older systems:
        // https://httptoolkit.com/blog/yaml-media-type-rfc/
        if( ["yml", "yaml"].contains(path.getExtension()) )
            return "application/yaml"

        return null
    }

    private static List asReferences(List values) {
        return values.collect { value -> ["@id": value["@id"]] }
    }

    private static List withoutNulls(List list) {
        return list.findAll { v -> v != null }
    }

    private static Map withoutNulls(Map map) {
        return map.findAll { k, v -> v != null }
    }

}
