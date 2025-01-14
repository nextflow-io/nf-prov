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
import nextflow.script.ScriptMeta
import org.apache.commons.io.FilenameUtils
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

    private Path path

    private boolean overwrite

    @Delegate
    private PathNormalizer normalizer

    // The final RO-Crate directory
    private Path crateRootDir
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

    private static final List<String> README_FILENAMES = List.of("README.md", "README.txt", "readme.md", "readme.txt", "Readme.md", "Readme.txt", "README")

    @Override
    void render(Session session, Set<TaskRun> tasks, Map<Path,Path> workflowOutputs) {
        // get workflow inputs
        final taskLookup = ProvHelper.getTaskLookup(tasks)
        final workflowInputs = ProvHelper.getWorkflowInputs(tasks, taskLookup)

        // get workflow metadata
        final metadata = session.workflowMetadata
        this.crateRootDir = path.getParent()
        this.workdir = session.workDir
        this.projectDir = metadata.projectDir
        this.normalizer = new PathNormalizer(metadata)

        final manifest = metadata.manifest
        final nextflowMeta = metadata.nextflow
        final scriptFile = metadata.getScriptFile()

        final formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        final dateStarted = formatter.format(metadata.start)
        final dateCompleted = formatter.format(metadata.complete)
        final nextflowVersion = nextflowMeta.version.toString()
        final params = session.params
        final wrrocParams = session.config.navigate('prov.formats.wrroc', [:]) as Map

        // warn about any output files outside of the crate directory
        workflowOutputs.each { source, target ->
            if( !target.startsWith(crateRootDir) )
                println "Workflow output file $target is outside of the RO-crate directory"
        }

        // save resolved config
        final configPath = crateRootDir.resolve("nextflow.config")
        configPath.text = session.config.toConfigObject()

        // save pipeline README file
        Map readmeFile = null

        for( final fileName : README_FILENAMES ) {
            final readmeFilePath = projectDir.resolve(fileName)
            if( !Files.exists(readmeFilePath) )
                continue

            final encoding = FilenameUtils.getExtension(fileName).equals("md")
                ? "text/markdown"
                : "text/plain"
            readmeFile = [
                "@id"           : fileName,
                "@type"         : "File",
                "name"          : fileName,
                "description"   : "This is the README file of the workflow.",
                "encodingFormat": encoding
            ]
            Files.copy(readmeFilePath, crateRootDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING)
            break
        }

        // Copy workflow into crate directory
        Files.copy(scriptFile, crateRootDir.resolve(scriptFile.getFileName()), StandardCopyOption.REPLACE_EXISTING)

        // Copy nextflow_schema_json into crate if it exists
        final schemaFile = scriptFile.getParent().resolve("nextflow_schema.json")
        // TODO Add to crate metadata
        if( Files.exists(schemaFile) )
            Files.copy(schemaFile, crateRootDir.resolve(schemaFile.getFileName()), StandardCopyOption.REPLACE_EXISTING)

        // create manifest
        final softwareApplicationId = UUID.randomUUID()
        final organizeActionId = UUID.randomUUID()

        // Process wrroc configuration options
        final agent = parseAgentInfo(wrrocParams)
        final organization = parseOrganizationInfo(wrrocParams)
        final publisherID = getPublisherID(wrrocParams, agent, organization)
        if( organization )
            agent.put("affiliation", ["@id": organization.get("@id")])

        // license information
        final license = [
            "@id"  : manifest.license,
            "@type": "CreativeWork"
        ]

        final formalParameters = params
            .collect { name, value ->
                withoutNulls([
                    "@id"           : "#${name}",
                    "@type"         : "FormalParameter",
                    // TODO: infer type from value at runtime
                    "additionalType": "String",
                    // "defaultValue": "",
                    "conformsTo"    : ["@id": "https://bioschemas.org/profiles/FormalParameter/1.0-RELEASE"],
                    "description"   : null,
                    "encodingFormat": getEncodingFormat(value),
                    // TODO: match to output if type is Path
                    // "workExample": ["@id": outputId],
                    "name"          : name,
                    // "valueRequired": "True"
                ])
            }

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
                    "@id"           : crateRootDir.relativize(target).toString(),
                    "@type"         : getType(target),
                    "name"          : target.name,
                    "description"   : null,
                    "encodingFormat": getEncodingFormat(target),
                    // TODO: create FormalParameter for each output file?
                    // "exampleOfWork": {"@id": "#reversed"}
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

        final createActions = tasks
            .collect { task ->
                final createAction = [
                    "@id"         : "#" + task.hash.toString(),
                    "@type"       : "CreateAction",
                    "name"        : task.getName(),
                    // TODO: There is no description for Nextflow processes?
                    //"description" : "",
                    // TODO: task doesn't contain startTime information. TaskHandler does, but is not available to WrrocRenderer
                    //"startTime": "".
                    // TODO: Same as for startTime
                    //"endTime": "",
                    "instrument"  : ["@id": "#" + task.processor.ownerScript.toString()],
                    "agent"       : ["@id": agent.get("@id")],
                    "object"      : task.getInputFilesMap().collect { name, source ->
                        ["@id": normalizePath(source)]
                    },
                    "result"      : ProvHelper.getTaskOutputs(task).collect { target ->
                        ["@id": normalizePath(target)]
                    },
                    "actionStatus": task.getExitStatus() == 0 ? "http://schema.org/CompletedActionStatus" : "http://schema.org/FailedActionStatus"
                ]

                // Add error message if there is one
                if (task.getExitStatus() != 0) {
                    createAction.put("error", task.getStderr())
                }

                return createAction
            }

        final processes = tasks
            .collect { task -> task.processor }
            .unique()

        final workflowSofwareApplications = processes
            .collect() { process ->
                final metaYaml = readMetaYaml(process)
                if (metaYaml == null) {
                    return [
                        "@id"    : "#" + process.ownerScript.toString(),
                        "@type"  : "SoftwareApplication",
                        "name"   : process.getName(),
                    ]
                }

                final moduleName = metaYaml.get('name') as String
                final toolNames = metaYaml.containsKey('tools')
                    ? metaYaml.get('tools').collect { tool ->
                        final entry = (tool as Map).entrySet().first()
                        entry.key as String
                    }
                    : []

                final parts = !toolNames.isEmpty()
                    ? toolNames.collect { name -> ["@id": moduleName + '-' + name] }
                    : null

                return [
                    "@id"    : "#" + process.ownerScript.toString(),
                    "@type"  : "SoftwareApplication",
                    "name"   : process.getName(),
                    "hasPart": parts
                ]
            }

        final toolSoftwareApplications = processes
            .collect { process -> readMetaYaml(process) }
            .findAll { metaYaml -> metaYaml != null }
            .collectMany { metaYaml ->
                final moduleName = metaYaml.get('name') as String
                final toolMaps = metaYaml.containsKey('tools')
                    ? metaYaml.get('tools').collect { tool -> tool as Map }
                    : []

                return toolMaps
                    .collect { toolMap ->
                        final entry = toolMap.entrySet().first()
                        final toolName = entry.key as String
                        final toolDescription = (entry.value as Map)?.get('description') as String
                        return [
                            "@id"         : moduleName + '-' + toolName,
                            "@type"       : "SoftwareApplication",
                            "name"        : toolName,
                            "description" : entry.value?.toString() ?: ""
                        ]
                    }
            }

        final howToSteps = processes
            .collect() { process ->
                [
                    "@id"        : metadata.projectName + "#main/" + process.getName(),
                    "@type"      : "HowToStep",
                    "workExample": ["@id": "#" + process.ownerScript.toString()],
                    "position"   : process.getId().toString()
                ]
            }

        final controlActions = processes
            .collect() { process ->
                final taskIds = tasks
                    .findAll { task -> task.processor == process }
                    .collect { task -> ["@id": "#" + task.hash.toString()] }

                return [
                    "@id"       : "#" + UUID.randomUUID(),
                    "@type"     : "ControlAction",
                    "instrument": ["@id": "${metadata.projectName}#main/${process.getName()}"],
                    "name"      : "orchestrate " + "${metadata.projectName}#main/${process.getName()}",
                    "object"    : taskIds
                ]
            }

        final configFile = [
            "@id"           : "nextflow.config",
            "@type"         : "File",
            "name"          : "Effective Nextflow configuration",
            "description"   : "This is the effective configuration during runtime compiled from all configuration sources.",
            "encodingFormat": "text/plain"
        ]

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
                    "name"       : "Workflow run of " + manifest.getName() ?: metadata.projectName,
                    "description": manifest.description ?: null,
                    "hasPart"    : withoutNulls([
                        ["@id": metadata.projectName],
                        ["@id": "nextflow.config"],
                        readmeFile ? ["@id": readmeFile["@id"]] : null,
                        *asReferences(inputFiles),
                        *asReferences(intermediateFiles),
                        *asReferences(outputFiles)
                    ]),
                    "mainEntity" : ["@id": metadata.projectName],
                    "mentions"   : [
                        ["@id": "#${session.uniqueId}"],
                        *asReferences(createActions)
                    ],
                    "license"    : license
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
                    "name"               : manifest.getName() ?: metadata.projectName,
                    "description"        : manifest.getDescription() ?: null,
                    "programmingLanguage": ["@id": "https://w3id.org/workflowhub/workflow-ro-crate#nextflow"],
                    "creator"            : manifest.getAuthor() ?: null,
                    "version"            : manifest.getVersion() ?: null,
                    "license"            : manifest.getLicense() ?: null,
                    "url"                : manifest.getHomePage() ?: null,
                    "encodingFormat"     : "application/nextflow",
                    "runtimePlatform"    : manifest.getNextflowVersion() ? "Nextflow " + manifest.getNextflowVersion() : null,
                    "hasPart"            : asReferences(workflowSofwareApplications),
                    "input"              : asReferences(formalParameters),
                    "output"             : [
                        // TODO: id of FormalParameter for each output file
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
                *workflowSofwareApplications,
                *toolSoftwareApplications,
                *formalParameters,
                [
                    "@id"  : "#${softwareApplicationId}",
                    "@type": "SoftwareApplication",
                    "name" : "Nextflow ${nextflowVersion}"
                ],
                *howToSteps,
                [
                    "@id"       : "#${organizeActionId}",
                    "@type"     : "OrganizeAction",
                    "agent"     : ["@id": agent.get("@id")],
                    "instrument": ["@id": "#${softwareApplicationId}"],
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
                *createActions,
                configFile,
                readmeFile,
                *inputFiles,
                *intermediateFiles,
                *outputFiles,
                *propertyValues,
                license,
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
     * Read meta.yaml (nf-core style) file for a given Nextflow process.
     *
     * @param   TaskProcessor processor Nextflow process
     * @return  Yaml as Map
     */
    static Map readMetaYaml(TaskProcessor processor) {
        final metaFile = ScriptMeta.get(processor.getOwnerScript()).getModuleDir().resolve('meta.yml')
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
     * @param source Path to file
     * @return the MIME type of the file, or null if it's not a file.
     */
    static String getEncodingFormat(Path source) {
        if( !(source && source.exists() && source.isFile()) )
            return null

        String mime = Files.probeContentType(source)
        if( mime )
            return mime

        // It seems that YAML has a media type only since beginning of 2024
        // Set this by hand if this is run on older systems:
        // https://httptoolkit.com/blog/yaml-media-type-rfc/
        final extension = FilenameUtils.getExtension(source.toString())
        if( ["yml", "yaml"].contains(extension) )
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
