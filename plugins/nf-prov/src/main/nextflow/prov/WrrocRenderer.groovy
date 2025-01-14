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
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import groovy.json.JsonOutput
import groovy.transform.CompileStatic
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
        final agent = parseAgentInfo(wrrocOpts)
        final organization = parseOrganizationInfo(wrrocOpts)
        final publisherId = getPublisherId(wrrocOpts, agent, organization)
        if( organization )
            agent["affiliation"] = ["@id": organization.get("@id")]

        // warn about any output files outside of the crate directory
        workflowOutputs.each { source, target ->
            if( !target.startsWith(crateDir) )
                println "Workflow output file $target is outside of the RO-crate directory"
        }

        // create manifest
        final softwareApplicationId = metadata.projectName + '#sa'
        final organizeActionId = metadata.projectName + '#organize'
        final datasetParts = []

        // -- license
        final license = [
            "@id"  : manifest.license,
            "@type": "CreativeWork"
        ]

        datasetParts.add(license)

        // -- readme file
        for( final fileName : README_FILENAMES ) {
            final readmePath = projectDir.resolve(fileName)
            if( !Files.exists(readmePath) )
                continue

            Files.copy(readmePath, crateDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING)

            datasetParts.add([
                "@id"           : fileName,
                "@type"         : "File",
                "name"          : fileName,
                "description"   : "The README file of the workflow.",
                "encodingFormat": getEncodingFormat(readmePath)
            ])
            break
        }

        // -- parameter schema
        final schemaPath = scriptFile.getParent().resolve("nextflow_schema.json")
        if( Files.exists(schemaPath) ) {
            final fileName = schemaPath.name

            Files.copy(schemaPath, crateDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING)
            datasetParts.add([
                "@id"           : fileName,
                "@type"         : "File",
                "name"          : fileName,
                "description"   : "The parameter schema of the workflow.",
                "encodingFormat": "application/json"
            ])
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
        // TODO: use parameter schema to populate additional fields
        // TODO: use parameter schema to add file params to crate
        // TODO: formal parameters for workflow output targets
        final formalParameters = params
            .collect { name, value ->
                withoutNulls([
                    "@id"           : getFormalParameterId(metadata.projectName, name),
                    "@type"         : "FormalParameter",
                    "conformsTo"    : ["@id": "https://bioschemas.org/profiles/FormalParameter/1.0-RELEASE"],
                    "encodingFormat": getEncodingFormat(value),
                    "name"          : name,
                ])
            }

        final propertyValues = params
            .collect { name, value ->
                final normalized =
                    (value instanceof List || value instanceof Map) ? JsonOutput.toJson(value)
                    : value instanceof CharSequence ? normalizePath(value.toString())
                    : value

                return [
                    "@id"          : "#${name}",
                    "@type"        : "PropertyValue",
                    "exampleOfWork": ["@id": getFormalParameterId(metadata.projectName, name)],
                    "name"         : name,
                    "value"        : normalized
                ]
            }

        // -- input, output, and intermediate files
        final inputFiles = workflowInputs
            .collect { source ->
                withoutNulls([
                    "@id"           : normalizePath(source),
                    "@type"         : getType(source),
                    "name"          : source.name,
                    "description"   : null,
                    "encodingFormat": getEncodingFormat(source),
                    //"fileType": "whatever",
                    // TODO: apply if matching param is found
                    // "exampleOfWork": ["@id": paramId]
                ])
            }

        final intermediateFiles = tasks.collectMany { task ->
            ProvHelper.getTaskOutputs(task).collect { target ->
                withoutNulls([
                    "@id"           : normalizePath(target),
                    "@type"         : getType(target),
                    "name"          : target.name,
                    "encodingFormat": getEncodingFormat(target),
                ])
            }
        }

        final outputFiles = workflowOutputs
            .collect { source, target ->
                withoutNulls([
                    "@id"           : crateDir.relativize(target).toString(),
                    "@type"         : getType(target),
                    "name"          : target.name,
                    "description"   : null,
                    "encodingFormat": getEncodingFormat(target),
                    // TODO: create FormalParameter for each output file?
                    // "exampleOfWork": {"@id": "#reversed"}
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
                meta.getDefinitions().findAll { defn -> defn instanceof ProcessDef }
            } as List<ProcessDef>

        final moduleSoftwareApplications = processDefs
            .collect() { process ->
                final metaYaml = readMetaYaml(process)
                if (metaYaml == null) {
                    return [
                        "@id"    : getModuleId(process),
                        "@type"  : "SoftwareApplication",
                        "name"   : process.getName(),
                    ]
                }

                final moduleName = metaYaml.get('name') as String
                final tools = metaYaml.getOrDefault('tools', []) as List
                final parts = tools.collect { tool ->
                    final entry = (tool as Map).entrySet().first()
                    final toolName = entry.key as String
                    ["@id": getToolId(moduleName, toolName)]
                }

                return [
                    "@id"    : getModuleId(process),
                    "@type"  : "SoftwareApplication",
                    "name"   : process.getBaseName(),
                    "hasPart": !parts.isEmpty() ? parts : null
                ]
            }

        final toolSoftwareApplications = processDefs
            .collect { process -> readMetaYaml(process) }
            .findAll { metaYaml -> metaYaml != null }
            .collectMany { metaYaml ->
                final moduleName = metaYaml.get('name') as String
                final tools = metaYaml.getOrDefault('tools', []) as List

                return tools
                    .collect { tool ->
                        final entry = (tool as Map).entrySet().first()
                        final toolName = entry.key as String
                        final toolDescription = (entry.value as Map)?.get('description') as String
                        return [
                            "@id"         : getToolId(moduleName, toolName),
                            "@type"       : "SoftwareApplication",
                            "name"        : toolName,
                            "description" : entry.value?.toString() ?: ""
                        ]
                    }
            }

        final howToSteps = taskProcessors
            .collect() { process ->
                [
                    "@id"        : getProcessStepId(metadata.projectName, process),
                    "@type"      : "HowToStep",
                    "workExample": ["@id": getModuleId(process)],
                    "position"   : process.getId()
                ]
            }

        final controlActions = taskProcessors
            .collect() { process ->
                final taskIds = tasks
                    .findAll { task -> task.processor == process }
                    .collect { task -> ["@id": "#" + task.hash.toString()] }

                return [
                    "@id"       : getProcessControlId(metadata.projectName, process),
                    "@type"     : "ControlAction",
                    "instrument": ["@id": getProcessStepId(metadata.projectName, process)],
                    "name"      : "Orchestrate process " + process.getName(),
                    "object"    : taskIds
                ]
            }

        // -- workflow execution
        final taskCreateActions = tasks
            .collect { task ->
                final result = [
                    "@id"         : "#" + task.hash.toString(),
                    "@type"       : "CreateAction",
                    "name"        : task.getName(),
                    // TODO: get description from meta yaml
                    //"description" : "",
                    "instrument"  : ["@id": getModuleId(task.processor)],
                    "agent"       : ["@id": agent.get("@id")],
                    "object"      : task.getInputFilesMap().collect { name, source ->
                        ["@id": normalizePath(source)]
                    },
                    "result"      : ProvHelper.getTaskOutputs(task).collect { target ->
                        ["@id": normalizePath(target)]
                    },
                    "actionStatus": task.exitStatus == 0 ? "http://schema.org/CompletedActionStatus" : "http://schema.org/FailedActionStatus"
                ]
                if( task.exitStatus != 0 )
                    result["error"] = task.stderr
                return result
            }

        final publishCreateActions = workflowOutputs
            .collect { source, target ->
                [
                    "@id"         : "publish#" + normalizePath(source),
                    "@type"       : "CreateAction",
                    "name"        : "publish",
                    "instrument"  : ["@id": softwareApplicationId],
                    "object"      : ["@id": normalizePath(source)],
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
                    "author"     : ["@id": agent.get("@id")],
                    "publisher"  : publisherId ? ["@id": publisherId] : null,
                    "datePublished": getDatePublished(),
                    "conformsTo" : [
                        ["@id": "https://w3id.org/ro/wfrun/process/0.1"],
                        ["@id": "https://w3id.org/ro/wfrun/workflow/0.1"],
                        ["@id": "https://w3id.org/ro/wfrun/provenance/0.1"],
                        ["@id": "https://w3id.org/workflowhub/workflow-ro-crate/1.0"]
                    ],
                    "name"       : "Workflow run of " + manifest.name ?: metadata.projectName,
                    "description": manifest.description ?: null,
                    "hasPart"    : withoutNulls([
                        ["@id": metadata.projectName],
                        *asReferences(datasetParts),
                        *asReferences(inputFiles),
                        *asReferences(intermediateFiles),
                        *asReferences(outputFiles)
                    ]),
                    "mainEntity" : ["@id": metadata.projectName],
                    "mentions"   : [
                        ["@id": "#${session.uniqueId}"],
                        *asReferences(taskCreateActions),
                        *asReferences(publishCreateActions)
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
                    "@id"                : metadata.projectName,
                    "@type"              : ["File", "SoftwareSourceCode", "ComputationalWorkflow", "HowTo"],
                    "conformsTo"         : ["@id": "https://bioschemas.org/profiles/ComputationalWorkflow/1.0-RELEASE"],
                    "name"               : manifest.name ?: metadata.projectName,
                    "description"        : manifest.description,
                    "programmingLanguage": ["@id": "https://w3id.org/workflowhub/workflow-ro-crate#nextflow"],
                    "creator"            : manifest.author,
                    "codeRepository"     : metadata.repository,
                    "version"            : metadata.commitId,
                    "license"            : manifest.license,
                    "url"                : manifest.homePage,
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
                *moduleSoftwareApplications,
                *toolSoftwareApplications,
                *formalParameters,
                [
                    "@id"  : softwareApplicationId,
                    "@type": "SoftwareApplication",
                    "name" : "Nextflow ${nextflowVersion}"
                ],
                *howToSteps,
                [
                    "@id"       : organizeActionId,
                    "@type"     : "OrganizeAction",
                    "agent"     : ["@id": agent.get("@id")],
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
                    "agent"     : ["@id": agent.get("@id")],
                    "name"      : "Nextflow workflow run ${session.uniqueId}",
                    "startTime" : dateStarted,
                    "endTime"   : dateCompleted,
                    "instrument": ["@id": metadata.projectName],
                    "object"    : [
                        *asReferences(propertyValues),
                        *asReferences(inputFiles),
                    ],
                    "result"    : asReferences(outputFiles)
                ],
                agent,
                organization,
                *contactPoints,
                *controlActions,
                *taskCreateActions,
                *publishCreateActions,
                *datasetParts,
                *propertyValues,
                *inputFiles,
                *intermediateFiles,
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
    private Map parseAgentInfo(Map opts) {
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
            final contactPointId = parseContactPointInfo(agentOpts)
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
    private Map parseOrganizationInfo(Map opts) {
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
            final contactPointId = parseContactPointInfo(orgOpts)
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
    private String parseContactPointInfo(Map opts) {
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
        if( opts.rar )
            contactPoint.url = opts.rar

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

        final publisherOpts = opts.publisher as Map
        if( !publisherOpts.containsKey("id") )
            return null

        final publisherId = publisherOpts.id

        // Check if the publisher id references either the agent or the organization
        final agentId = agent?["@id"]
        final organizationId = organization?["@id"]
        if( publisherId != agentId && publisherId != organizationId )
            return null

        return publisherId
    }

    /**
     * Get the canonical name of a module script.
     *
     * @param projectName
     * @param name
     */
    private String getFormalParameterId(String projectName, String name) {
        return "${projectName}#param#${name}"
    }

    /**
     * Get the canonical name of a module script.
     *
     * @param process
     */
    private String getModuleId(ProcessDef process) {
        final scriptPath = ScriptMeta.get(process.getOwner()).getScriptPath().normalize()
        return normalizePath(scriptPath)
    }

    /**
     * Get the canonical name of a module script.
     *
     * @param process
     */
    private String getModuleId(TaskProcessor process) {
        final scriptPath = ScriptMeta.get(process.getOwnerScript()).getScriptPath().normalize()
        return normalizePath(scriptPath)
    }

    /**
     * Get the canonical name of a tool used by a module.
     *
     * @param moduleName
     * @param toolName
     */
    private static String getToolId(String moduleName, String toolName) {
        return "${moduleName}#${toolName}"
    }

    /**
     * Get the canonical name of a process in the workflow DAG.
     *
     * @param projectName
     * @param process
     */
    private static String getProcessControlId(String projectName, TaskProcessor process) {
        return "${projectName}#control#${process.getName()}"
    }

    private static String getProcessStepId(String projectName, TaskProcessor process) {
        return "${projectName}#step#${process.getName()}"
    }

    /**
     * Get the nf-core meta.yml of a Nextflow module as a map.
     *
     * @param process
     */
    private static Map readMetaYaml(ProcessDef process) {
        final metaFile = ScriptMeta.get(process.getOwner()).getModuleDir().resolve('meta.yml')
        return Files.exists(metaFile)
            ? new Yaml().load(metaFile.text) as Map
            : null
    }

    /**
     * Check if a Path is a file or a directory and return corresponding "@type"
     *
     * @param path The path to be checked
     * @return type Either "File" or "Directory"
     */
    private static String getType(Path path) {
        return path.isDirectory()
            ? "Directory"
            : "File"
    }

    /**
     * Get the encodingFormat of a file as MIME Type.
     *
     * @param value A value that may be a file
     * @return the MIME type of the value, or null if it's not a file.
     */
    private static String getEncodingFormat(Object value) {

        return value instanceof String
            ? getEncodingFormat(Path.of(value))
            : null
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

    private static <E> List<E> withoutNulls(List<E> list) {
        return list.findAll { v -> v != null }
    }

    private static <K,V> Map<K,V> withoutNulls(Map<K,V> map) {
        return map.findAll { k, v -> v != null }
    }

}
