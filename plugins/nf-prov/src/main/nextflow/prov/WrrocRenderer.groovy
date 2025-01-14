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

    // The final RO-Crate directory
    private Path createDir
    // Nextflow work directory
    private Path workdir
    // Nextflow pipeline directory (contains main.nf, assets, etc.)
    private Path projectDir
    // List of contactPoints (people, organizations) to be added to ro-crate-metadata.json
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
        this.createDir = path.getParent()
        this.workdir = session.workDir
        this.projectDir = metadata.projectDir
        this.normalizer = new PathNormalizer(metadata)

        final manifest = metadata.manifest
        final scriptFile = metadata.getScriptFile()

        final formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        final dateStarted = formatter.format(metadata.start)
        final dateCompleted = formatter.format(metadata.complete)
        final nextflowVersion = metadata.nextflow.version.toString()
        final params = session.params
        final wrrocParams = session.config.navigate('prov.formats.wrroc', [:]) as Map

        // warn about any output files outside of the crate directory
        workflowOutputs.each { source, target ->
            if( !target.startsWith(createDir) )
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

            Files.copy(readmePath, createDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING)

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

            Files.copy(schemaPath, createDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING)
            datasetParts.add([
                "@id"           : fileName,
                "@type"         : "File",
                "name"          : fileName,
                "description"   : "The parameter schema of the workflow.",
                "encodingFormat": "application/json"
            ])
        }

        // -- resolved config
        final configPath = createDir.resolve("nextflow.config")
        configPath.text = ConfigHelper.toCanonicalString(session.config, true)

        datasetParts.add([
            "@id"           : "nextflow.config",
            "@type"         : "File",
            "name"          : "Resolved Nextflow configuration",
            "description"   : "The resolved Nextflow configuration for the workflow run.",
            "encodingFormat": "text/plain"
        ])

        // Process wrroc configuration options
        final agent = parseAgentInfo(wrrocParams)
        final organization = parseOrganizationInfo(wrrocParams)
        final publisherID = getPublisherID(wrrocParams, agent, organization)
        if( organization )
            agent.put("affiliation", ["@id": organization.get("@id")])

        // -- pipeline parameters
        // TODO: use parameter schema to populate additional fields
        // TODO: use parameter schema to add file params to crate
        final formalParameters = params
            .collect { name, value ->
                withoutNulls([
                    "@id"           : "#${name}",
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
                    "@id"          : "#${name}-pv",
                    "@type"        : "PropertyValue",
                    "exampleOfWork": ["@id": "#${name}"],
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
                    "@id"           : createDir.relativize(target).toString(),
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
                    "@id"        : getProcessHowToId(metadata.projectName, process),
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
                    "instrument": ["@id": getProcessHowToId(metadata.projectName, process)],
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
                    "result"      : ["@id": createDir.relativize(target).toString()],
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
                    "publisher"  : publisherID ? ["@id": publisherID] : null,
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
                        *asReferences(inputFiles),
                        *asReferences(propertyValues)
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
                *inputFiles,
                *intermediateFiles,
                *outputFiles,
                *propertyValues,
            ])
        ]

        // render manifest to JSON file
        path.text = JsonOutput.prettyPrint(JsonOutput.toJson(wrroc))
    }

    static String getDatePublished() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_DATE)
    }

    /**
     * Parse information about agent running the workflow from parameters
     *
     * @param params Nextflow parameters
     * @return       Map describing agent via '@id'. 'orcid' and 'name'
     */
    Map parseAgentInfo(Map params) {
        final agent = [:]

        if (! params.containsKey("agent"))
            return null

        Map agentMap = params["agent"] as Map

        agent.put("@id", agentMap.containsKey("orcid") ? agentMap.get("orcid") : "agent-1")
        agent.put("@type", "Person")
        if(agentMap.containsKey("name"))
            agent.put("name", agentMap.get("name"))

        // Check for contact information
        if(agentMap.containsKey("email") || agentMap.containsKey("phone")) {
            // Add contact point to ro-crate-metadata.json
            String contactPointID = parseContactPointInfo(agentMap)
            if(contactPointID)
                agent.put("contactPoint", ["@id": contactPointID ])
        }

        return agent
    }


    /**
     * Parse information about organization agent running the workflow belongs to.
     *
     * @param params Nextflow parameters
     * @return       Map describing organization via '@id'. 'orcid' and 'name'
     */
    Map parseOrganizationInfo(Map params) {
        final org = [:]

        if (! params.containsKey("organization"))
            return null

        Map orgMap = params["organization"] as Map
        org.put("@id", orgMap.containsKey("ror") ? orgMap.get("ror") : "organization-1")
        org.put("@type", "Organization")
        if(orgMap.containsKey("name"))
            org.put("name", orgMap.get("name"))

        // Check for contact information
        if(orgMap.containsKey("email") || orgMap.containsKey("phone")) {
            // Add contact point to ro-crate-metadata.json
            String contactPointID = parseContactPointInfo(orgMap)
            if(contactPointID)
                org.put("contactPoint", ["@id": contactPointID ])
        }

        return org
    }


    /**
     * Parse information about contact point and add to contactPoints list.
     *
     * @param params Map describing an agent or organization
     * @return       ID of the contactPoint
     */
    String parseContactPointInfo(Map map) {

        String contactPointID = ""
        final contactPoint = [:]

        // Prefer email for the contact point ID
        if(map.containsKey("email"))
            contactPointID = "mailto:" + map.get("email")
        else if(map.containsKey("phone"))
            contactPointID = map.get("phone")
        else
            return null

        contactPoint.put("@id", contactPointID)
        contactPoint.put("@type", "ContactPoint")
        if(map.containsKey("contactType"))
            contactPoint.put("contactType", map.get("contactType"))
        if(map.containsKey("email"))
            contactPoint.put("email", map.get("email"))
        if(map.containsKey("phone"))
            contactPoint.put("phone", map.get("phone"))
        if(map.containsKey("orcid"))
            contactPoint.put("url", map.get("orcid"))
        if(map.containsKey("rar"))
            contactPoint.put("url", map.get("rar"))

        contactPoints.add(contactPoint)
        return contactPointID
    }


    /**
     * Parse information about the RO-Crate publisher.
     *
     * @param params Nextflow parameters
     * @return       Publisher ID
     */
    static String getPublisherID(Map params, Map agent, Map organization) {

        if (! params.containsKey("publisher"))
            return null

        Map publisherMap = params["publisher"] as Map
        if (! publisherMap.containsKey("id"))
            return null

        String publisherID = publisherMap.get("id")
        String agentID = ""
        String organizationID = ""
        if (agent)
            agentID = agent.get("@id")
        if (organization)
            organizationID = organization.get("@id")

        // Check if the publisher ID references either the organization or the agent
        if (publisherID != agentID && publisherID != organizationID)
            return null

        return publisherID
    }

    /**
     * Get the canonical name of a module script.
     *
     * @param process
     */
    String getModuleId(ProcessDef process) {
        final scriptPath = ScriptMeta.get(process.getOwner()).getScriptPath().normalize()
        return normalizePath(scriptPath)
    }

    /**
     * Get the canonical name of a module script.
     *
     * @param process
     */
    String getModuleId(TaskProcessor process) {
        final scriptPath = ScriptMeta.get(process.getOwnerScript()).getScriptPath().normalize()
        return normalizePath(scriptPath)
    }

    /**
     * Get the canonical name of a tool used by a module.
     *
     * @param moduleName
     * @param toolName
     */
    String getToolId(String moduleName, String toolName) {
        return "${moduleName}#${toolName}"
    }

    /**
     * Get the canonical name of a process in the workflow DAG.
     *
     * @param projectName
     * @param process
     */
    static String getProcessControlId(String projectName, TaskProcessor process) {
        return "${projectName}#control#${process.getName()}"
    }

    static String getProcessHowToId(String projectName, TaskProcessor process) {
        return "${projectName}#howto#${process.getName()}"
    }

    /**
     * Get the nf-core meta.yml of a Nextflow module as a map.
     *
     * @param process
     */
    static Map readMetaYaml(ProcessDef process) {
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
    static String getType(Path path) {
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
    static String getEncodingFormat(Object value) {

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
    static String getEncodingFormat(Path path) {
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
