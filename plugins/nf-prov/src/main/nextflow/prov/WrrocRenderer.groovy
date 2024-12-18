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

import nextflow.config.ConfigMap
import nextflow.file.FileHolder
import nextflow.script.params.FileInParam
import nextflow.script.params.FileOutParam
import nextflow.script.ScriptMeta

import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import nextflow.Session
import nextflow.processor.TaskRun
import nextflow.processor.*
import org.yaml.snakeyaml.Yaml

import org.apache.commons.io.FilenameUtils

/**
 * Renderer for the Provenance Run RO Crate format.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 * @author Felix Bartusch <felix.bartusch@uni-tuebingen.de>
 */
@CompileStatic
class WrrocRenderer implements Renderer {

    private Path path
    // The final RO-Crate directory
    private Path crateRootDir
    // Nextflow work directory
    private Path workdir
    // Nextflow pipeline directory (contains main.nf, assets, etc.)
    private Path projectDir

    private LinkedHashMap agent
    private LinkedHashMap organization
    // List of contactPoints (people, organizations) to be added to ro-crate-metadata.json
    private List<LinkedHashMap> contactPoints = []
    private String publisherID

    private boolean overwrite

    @Delegate
    private PathNormalizer normalizer

    WrrocRenderer(Map opts) {
        path = opts.file as Path
        overwrite = opts.overwrite as Boolean

        ProvHelper.checkFileOverwrite(path, overwrite)
    }

    @Override
    void render(Session session, Set<TaskRun> tasks, Map<Path, Path> workflowOutputs) {

        final params = session.getBinding().getParams() as Map
        final configMap = new ConfigMap(session.getConfig())

        // Set RO-Crate Root and workdir
        this.crateRootDir = Path.of(params['outdir'].toString()).toAbsolutePath()
        this.workdir = session.getWorkDir()
        this.projectDir = session.getWorkflowMetadata().getProjectDir()

        // get workflow inputs
        final taskLookup = ProvHelper.getTaskLookup(tasks)
        final workflowInputs = ProvHelper.getWorkflowInputs(tasks, taskLookup)

        // Add intermediate input files (produced by workflow tasks and consumed by other tasks)
        workflowInputs.addAll(getIntermediateInputFiles(tasks, workflowInputs));
        final Map<Path, Path> workflowInputMapping = getWorkflowInputMapping(workflowInputs)

        // Add intermediate output files (produced by workflow tasks and consumed by other tasks)
        workflowOutputs.putAll(getIntermediateOutputFiles(tasks, workflowOutputs));

        // Copy workflow input files into RO-Crate
        workflowInputMapping.each {source, dest ->

            if (Files.isDirectory(source)) {
                // Recursively copy directory and its contents
                Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
                    @Override
                    FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        Path targetDir = dest.resolve(source.relativize(dir))
                        Files.createDirectories(targetDir)
                        return FileVisitResult.CONTINUE
                    }

                    @Override
                    FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Path targetFile = dest.resolve(source.relativize(file))
                        if (!Files.exists(targetFile))
                            Files.copy(file, targetFile)
                        return FileVisitResult.CONTINUE
                    }
                })
            } else {
                try {
                    Files.createDirectories(dest.getParent())
                    if (!Files.exists(dest))
                        Files.copy(source, dest)
                } catch (Exception e) {
                    println "workflowInput: Failed to copy $source to $dest: ${e.message}"
                }
            }
        }

        // Copy workflow output files into RO-Crate
        workflowOutputs.each {source, dest ->

            if (Files.isDirectory(source)) {
                // Recursively copy directory and its contents
                Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
                    @Override
                    FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        Path targetDir = dest.resolve(source.relativize(dir))
                        Files.createDirectories(targetDir)
                        return FileVisitResult.CONTINUE
                    }

                    @Override
                    FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Path targetFile = dest.resolve(source.relativize(file))
                        if (!Files.exists(targetFile))
                            Files.copy(file, targetFile)
                        return FileVisitResult.CONTINUE
                    }
                })
            } else {
                try {
                    Files.createDirectories(dest.getParent())
                    Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING)
                } catch (Exception e) {
                    println "workflowOutput Failed to copy $source to $dest: ${e.message}"
                }
            }
        }

        // get workflow config and store it in crate
        Path configFilePath = crateRootDir.resolve("nextflow.config")
        FileWriter configFileWriter = new FileWriter(configFilePath.toString())
        configMap.toConfigObject().writeTo(configFileWriter)

        // get workflow README file and store it in crate
        boolean readmeExists = false
        List<String> readmeFiles = ["README.md", "README.txt", "readme.md", "readme.txt", "Readme.md", "Readme.txt", "README"]
        Path readmeFilePath = null
        String readmeFileName = null
        String readmeFileExtension = null
        String readmeFileEncoding = null

        for (String fileName : readmeFiles) {
            Path potentialReadmePath = projectDir.resolve(fileName)
            if (Files.exists(potentialReadmePath)) {
                readmeExists = true
                readmeFilePath = potentialReadmePath
                readmeFileName = fileName
                if (FilenameUtils.getExtension(fileName).equals("md"))
                    readmeFileEncoding = "text/markdown"
                else
                    readmeFileEncoding = "text/plain"
                break
            }
        }
        def readmeFile = null

        // Copy the README file into RO-Crate if it exists
        if (readmeExists) {
            Files.copy(readmeFilePath, crateRootDir.resolve(readmeFileName), StandardCopyOption.REPLACE_EXISTING)
            readmeFile = 
                [
                    "@id"           : readmeFileName,
                    "@type"         : "File",
                    "name"          : readmeFileName,
                    "description"   : "This is the README file of the workflow.",
                    "encodingFormat": readmeFileEncoding
                ]
        }

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
        final wrrocParams = session.config.prov["formats"]["wrroc"] as Map

        // Copy workflow into crate directory
        Files.copy(scriptFile, crateRootDir.resolve(scriptFile.getFileName()), StandardCopyOption.REPLACE_EXISTING)

        // Copy nextflow_schema_json into crate if it exists
        final schemaFile = scriptFile.getParent().resolve("nextflow_schema.json")
        // TODO Add to crate metadata
        if (Files.exists(schemaFile))
            Files.copy(schemaFile, crateRootDir.resolve(schemaFile.getFileName()), StandardCopyOption.REPLACE_EXISTING)


        // create manifest
        final softwareApplicationId = UUID.randomUUID()
        final organizeActionId = UUID.randomUUID()

        // Process wrroc configuration options
        agent = parseAgentInfo(wrrocParams)
        organization = parseOrganizationInfo(wrrocParams)
        publisherID = getPublisherID(wrrocParams, agent, organization)
        if(organization)
            agent.put("affiliation", ["@id": organization.get("@id")])
        //license = parseLicenseInfo(wrrocParams)


        // license information
        boolean licenseURLvalid = false
        String licenseString = null;
        URI licenseURL = null
        Map license = null
        if (wrrocParams.containsKey("license")) {
            licenseString = wrrocParams.get("license")
            try {
                licenseURL = new URL(licenseString).toURI();
                licenseURLvalid = true

                // Entity for license URL
                license = [
                    "@id"  : licenseURL.toString(),
                    "@type": "CreativeWork"
                ]
            } catch (Exception e) {
                licenseURLvalid = false
            }
        }

        final formalParameters = params
            .collect { name, value ->
                [
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
                ].findAll { it.value != null }
            }

        final inputFiles = workflowInputMapping
            .collect { source, target ->
                [
                    "@id"           : crateRootDir.relativize(target).toString(),
                    "@type"         : getType(source),
                    "name"          : target.name,
                    "description"   : null,
                    "encodingFormat": getEncodingFormat(source, target),
                    //"fileType": "whatever",
                    // TODO: apply if matching param is found
                    // "exampleOfWork": ["@id": paramId]
                ].findAll { it.value != null }
            }

        final outputFiles = workflowOutputs
            .collect { source, target ->
                [
                    "@id"           : crateRootDir.relativize(target).toString(),
                    "@type"         : getType(source),
                    "name"          : target.name,
                    "description"   : null,
                    "encodingFormat": getEncodingFormat(source, target),
                    // TODO: create FormalParameter for each output file?
                    // "exampleOfWork": {"@id": "#reversed"}
                ].findAll { it.value != null }
            }

        // Combine both, inputFiles and outputFiles into one list. Remove duplicates that occur when an intermediate
        // file is output of a task and input of another task.
        //Map<String, LinkedHashMap<String, String>> combinedInputOutputMap = [:]
        Map combinedInputOutputMap = [:]

        inputFiles.each { entry ->
            combinedInputOutputMap[entry['@id']] = entry
        }
        // Overwriting if 'id' already exists
        outputFiles.each { entry ->
            combinedInputOutputMap[entry['@id']] = entry
        }
        List uniqueInputOutputFiles = combinedInputOutputMap.values().toList()

        final propertyValues = params
            .collect { name, value ->
                [
                    "@id"          : "#${name}-pv",
                    "@type"        : "PropertyValue",
                    "exampleOfWork": ["@id": "#${name}"],
                    "name"         : name,
                    "value"        : isNested(value) ? JsonOutput.toJson(value) : value
                ]
            }

        // Maps used for finding tasks/CreateActions corresponding to a Nextflow process
        Map<String, List> processToTasks = [:].withDefault { [] }

        def createActions = tasks
            .collect { task ->
                List<String> resultFileIDs = []

                // Collect output files of the path
                List<Path> outputFileList = []
                for (taskOutputParam in task.getOutputsByType(FileOutParam)) {

                    if (taskOutputParam.getValue() instanceof Path) {
                        outputFileList.add(taskOutputParam.getValue() as Path)
                        continue
                    }

                    for (taskOutputFile in taskOutputParam.getValue()) {
                        // Path to file in workdir
                        outputFileList.add(Path.of(taskOutputFile.toString()))
                    }
                }

                // Check if the output files have a mapping in workflowOutputs
                for (outputFile in outputFileList) {
                    if (workflowOutputs.containsKey(outputFile)) {
                        resultFileIDs.add(crateRootDir.relativize(workflowOutputs.get(outputFile)).toString())
                    } else {
                        System.out.println("taskOutput not contained in workflowOutputs list: " + outputFile)
                    }
                }

                List<String> objectFileIDs = []
                for (taskInputParam in task.getInputsByType(FileInParam)) {
                    for (taskInputFileHolder in taskInputParam.getValue()) {
                        FileHolder holder = (FileHolder) taskInputFileHolder
                        Path taskInputFilePath = holder.getStorePath()

                        if (workflowInputs.contains(taskInputFilePath)) {
                            // The mapping of input files to their path in the RO-Crate is only available for files we
                            // expect (e.g. files in workdir and pipeline assets). Have to handle unexpected files ...
                            try {
                                objectFileIDs.add(crateRootDir.relativize(workflowInputMapping.get(taskInputFilePath)).toString())
                            } catch(Exception e) {
                                System.out.println("Unexpected input file: " + taskInputFilePath.toString())
                            }
                        } else {
                            System.out.println("taskInput not contained in workflowInputs list: " + taskInputFilePath)
                        }
                    }
                }

                def createAction = [
                    "@id"         : "#" + task.getHash().toString(),
                    "@type"       : "CreateAction",
                    "name"        : task.getName(),
                    // TODO: There is no description for Nextflow processes?
                    //"description" : "",
                    // TODO: task doesn't contain startTime information. TaskHandler does, but is not available to WrrocRenderer
                    //"startTime": "".
                    // TODO: Same as for startTime
                    //"endTime": "",
                    "instrument"  : ["@id": "#" + task.getProcessor().ownerScript.toString()],
                    "agent"       : ["@id": agent.get("@id").toString()],
                    "object"      : objectFileIDs.collect(file -> ["@id": file]),
                    "result"      : resultFileIDs.collect(file -> ["@id": file]),
                    "actionStatus": task.getExitStatus() == 0 ? "http://schema.org/CompletedActionStatus" : "http://schema.org/FailedActionStatus"
                ]

                // Add error message if there is one
                if (task.getExitStatus() != 0) {
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
            .collect() { process ->
                def metaYaml = readMetaYaml(process)
                if (metaYaml == null) {
                    return [
                        "@id"    : "#" + process.ownerScript.toString(),
                        "@type"  : "SoftwareApplication",
                        "name"   : process.getName(),
                    ]
                }
                
                def moduleName = metaYaml.get('name') as String
                def toolNames = []
                
                metaYaml.get('tools')?.each { tool ->
                    def entry = (tool as Map).entrySet().first()
                    def toolName = entry.key as String
                    toolNames << toolName
                }
                
                [
                    "@id"    : "#" + process.ownerScript.toString(),
                    "@type"  : "SoftwareApplication",
                    "name"   : process.getName(),
                    "hasPart": toolNames.isEmpty() ? null : toolNames.collect { name -> ["@id": moduleName + '-' + name] }
                ]
            }

        final perTool = nextflowProcesses
            .collect() { process ->
                def metaYaml = readMetaYaml(process)
                if (metaYaml == null) {
                    return null
                }

                def moduleName = metaYaml.get('name') as String
                def listOfToolMaps = []
                metaYaml.get('tools')?.each { tool -> listOfToolMaps.add(tool as Map) }

                def softwareMaps = listOfToolMaps.collect { toolMap ->
                    def entry = (toolMap as Map).entrySet().first()
                    def toolName = entry.key as String
                    def toolDescription = (entry.value as Map)?.get('description') as String
                    [(toolName): toolDescription]
                }

                // Create a list of SoftwareApplication entries
                def softwareApplications = softwareMaps.collect { softwareMap ->
                    def entry = (softwareMap as Map).entrySet().first()
                    def toolName = entry.key as String
                    [
                        "@id"         : moduleName + '-' + toolName,
                        "@type"       : "SoftwareApplication",
                        "name"        : toolName,
                        "description" : entry.value?.toString() ?: ""
                    ]
                }

                return softwareApplications
            }.findAll { it != null }.flatten()

        final howToSteps = nextflowProcesses
            .collect() { process ->
                [
                    "@id"        : metadata.projectName + "#main/" + process.getName(),
                    "@type"      : "HowToStep",
                    "workExample": ["@id": "#" + process.ownerScript.toString()],
                    "position"   : process.getId().toString()
                ]
            }

        final controlActions = nextflowProcesses
            .collect() { process ->
                [
                    "@id"       : "#" + UUID.randomUUID(),
                    "@type"     : "ControlAction",
                    "instrument": ["@id": "${metadata.projectName}#main/${process.getName()}"],
                    "name"      : "orchestrate " + "${metadata.projectName}#main/${process.getName()}",
                    "object"    : processToTasks[process.getId().toString()].collect({ taskID ->
                        ["@id": taskID]
                    })
                ]
            }

        final configFile =
            [
                "@id"           : "nextflow.config",
                "@type"         : "File",
                "name"          : "Effective Nextflow configuration",
                "description"   : "This is the effective configuration during runtime compiled from all configuration sources.",
                "encodingFormat": "text/plain"
            ]

        final wrroc = [
            "@context": "https://w3id.org/ro/crate/1.1/context",
            "@graph"  : [
                [
                    "@id"       : path.name,
                    "@type"     : "CreativeWork",
                    "about"     : ["@id": "./"],
                    "conformsTo": [
                        ["@id": "https://w3id.org/ro/crate/1.1"],
                        ["@id": "https://w3id.org/workflowhub/workflow-ro-crate/1.0"]
                    ]
                ],
                [
                    "@id"        : "./",
                    "@type"      : "Dataset",
                    "author"     : ["@id": agent.get("@id").toString()],
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
                    "hasPart"    : [
                        ["@id": metadata.projectName],
                        ["@id": "nextflow.config"],
                        readmeExists ? ["@id": readmeFile.get("@id")] : null,
                        *uniqueInputOutputFiles.collect(file -> ["@id": file["@id"]])
                    ].findAll { it != null },
                    "mainEntity" : ["@id": metadata.projectName],
                    "mentions"   : [
                        ["@id": "#${session.uniqueId}"],
                        *createActions.collect(createAction -> ["@id": createAction["@id"]])
                    ],
                    "license"    : licenseURLvalid ? ["@id": licenseURL.toString()] : licenseString
                ].findAll { it.value != null },
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
                [
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
                    "hasPart"            : wfSofwareApplications.collect(sa ->
                        ["@id": sa["@id"]]
                    ),
                    "input"              : formalParameters.collect(fp ->
                        ["@id": fp["@id"]]
                    ),
                    "output"             : [
                        // TODO: id of FormalParameter for each output file
                    ],
                    "step"               : howToSteps.collect(step ->
                        ["@id": step["@id"]]
                    ),
                ].findAll { it.value != null },
                [
                    "@id"       : "https://w3id.org/workflowhub/workflow-ro-crate#nextflow",
                    "@type"     : "ComputerLanguage",
                    "name"      : "Nextflow",
                    "identifier": "https://www.nextflow.io/",
                    "url"       : "https://www.nextflow.io/",
                    "version"   : nextflowVersion
                ],
                *wfSofwareApplications,
                *perTool,
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
                    "agent"     : ["@id": agent.get("@id").toString()],
                    "instrument": ["@id": "#${softwareApplicationId}"],
                    "name"      : "Run of Nextflow ${nextflowVersion}",
                    "object"    : [
                        *controlActions.collect(action -> ["@id": action["@id"]])
                    ],
                    "result"    : ["@id": "#${session.uniqueId}"],
                    "startTime" : dateStarted,
                    "endTime"   : dateCompleted
                ],
                [
                    "@id"       : "#${session.uniqueId}",
                    "@type"     : "CreateAction",
                    "agent"     : ["@id": agent.get("@id").toString()],
                    "name"      : "Nextflow workflow run ${session.uniqueId}",
                    "startTime" : dateStarted,
                    "endTime"   : dateCompleted,
                    "instrument": ["@id": metadata.projectName],
                    "object"    : [
                        *inputFiles.collect(file -> ["@id": file["@id"]]),
                        *propertyValues.collect(pv -> ["@id": pv["@id"]])
                    ],
                    "result"    : outputFiles.collect(file ->
                        ["@id": file["@id"]]
                    )
                ],
                *[agent],
                *[organization],
                *contactPoints,
                *controlActions,
                *createActions,
                configFile,
                readmeFile,
                *uniqueInputOutputFiles,
                *propertyValues,
                license,
            ].findAll { it != null }
        ]

        // render manifest to JSON file
        path.text = JsonOutput.prettyPrint(JsonOutput.toJson(wrroc))
    }

    static Set<Path> getIntermediateInputFiles(Set<TaskRun> tasks, Set<Path> workflowInputs) {
        Set<Path> intermediateInputFiles = []

        tasks.collect { task ->
            for (taskInputParam in task.getInputsByType(FileInParam)) {
                for (taskInputFileHolder in taskInputParam.getValue()) {
                    FileHolder holder = (FileHolder) taskInputFileHolder
                    Path taskInputFilePath = holder.getStorePath()

                    if (!workflowInputs.contains(taskInputFilePath)) {
                        intermediateInputFiles.add(taskInputFilePath)
                    }
                }
            }
        }

        return intermediateInputFiles
    }

    def Map<Path, Path> getIntermediateOutputFiles(Set<TaskRun> tasks, Map<Path, Path> workflowOutputs) {

        List<Path> intermediateOutputFilesList = []
        Map<Path, Path> intermediateOutputFilesMap = [:]

        tasks.each { task ->
            for (taskOutputParam in task.getOutputsByType(FileOutParam)) {

                // If the param is a Path, just add it to the intermediate list
                if (taskOutputParam.getValue() instanceof Path) {
                    intermediateOutputFilesList.add(taskOutputParam.getValue() as Path)
                    continue
                }

                for (taskOutputFile in taskOutputParam.getValue()) {
                    intermediateOutputFilesList.add(taskOutputFile as Path)
                }
            }
        }

        // Iterate over the file list and create the mapping
        for (outputFile in intermediateOutputFilesList) {
            if (!workflowOutputs.containsKey(outputFile)) {

                // Find the relative path from workdir
                Path relativePath = workdir.relativize(outputFile)

                // Build the new path by combining crateRootDir and the relative part
                Path outputFileInCrate = crateRootDir.resolve(workdir.fileName).resolve(relativePath)

                Files.createDirectories(outputFileInCrate.parent)
                intermediateOutputFilesMap.put(outputFile, outputFileInCrate)
            }
        }

        return intermediateOutputFilesMap
    }

    /**
     * Map input files from Nextflow workdir into the RO-Crate.
     *
     * @param paths Input file paths on the file system
     * @return      Map of input file paths into the RO-Crate
     */
    def Map<Path, Path> getWorkflowInputMapping(Set<Path> paths) {

        // The resulting mapping
        Map<Path, Path> workflowInputMapping = [:]

        // Nextflow asset directory
        Path assetDir = projectDir.resolve("assets")

        // pipeline_info directory. Although located in the result directory, it is used as input for MultiQC
        Path pipelineInfoDir = crateRootDir.resolve("pipeline_info")

        paths.collect { inputPath ->

            // Depending on where the input file is stored, use different Paths for the parent directory.
            // We assume that an input file is either stored in the workdir or in the pipeline's asset directory.
            Path parentDir = null
            if (inputPath.startsWith(workdir))
                parentDir = workdir
            else if (inputPath.startsWith(assetDir))
                parentDir = assetDir
            else if (inputPath.startsWith(pipelineInfoDir))
                parentDir = pipelineInfoDir


            // Ignore file with unkown (e.g. null) parentDir
            if(parentDir) {
                Path relativePath = parentDir.relativize(inputPath)
                Path outputFileInCrate = crateRootDir.resolve(parentDir.fileName).resolve(relativePath)
                workflowInputMapping.put(inputPath, outputFileInCrate)
            } else {
                // All other files are simple copied into the crate with their absolute path into the crate root
                Path relativePath = Path.of(inputPath.toString().substring(1))
                Path outputFileInCrate = crateRootDir.resolve(relativePath)
                workflowInputMapping.put(inputPath, outputFileInCrate)
            }
        }

        return workflowInputMapping
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
    def LinkedHashMap parseAgentInfo(Map params) {
        final LinkedHashMap agent = new LinkedHashMap()

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
    def LinkedHashMap parseOrganizationInfo(Map params) {
        final LinkedHashMap org = new LinkedHashMap()

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
    def String parseContactPointInfo(Map map) {

        String contactPointID = ""
        final LinkedHashMap contactPoint = new LinkedHashMap()

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
    static def String getPublisherID(Map params, Map agent, Map organization) {

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
        Path metaFile = ScriptMeta.get(processor.getOwnerScript()).getModuleDir().resolve('meta.yml')

        if (Files.exists(metaFile)) {
            Yaml yaml = new Yaml()
            return yaml.load(metaFile.text) as Map
            }

        return null
        }


    /**
     * Check if a groovy object contains nested structures, e.g. will not be flattened when serialized as JSON
     *
     * @param obj The object to be checked
     * @return    true if the object contains nested structures
     */
    static def boolean isNested(Object obj) {
        return (obj instanceof Map || obj instanceof List)
    }

    /**
     * Check if a Path is a file or a directory and return corresponding "@type"
     *
     * @param path The path to be checked
     * @return type Either "File" or "Directory"
     */
    static def String getType(Path path) {
        String type = "File"

        if(path.isDirectory())
            type = "Directory"

        return type
    }

    /**
     * Get the encodingFormat of a file as MIME Type.
     *
     * @param object An object that may be a file
     * @return the MIME type of the object or null, if it's not a file.
     */
    static def String getEncodingFormat(Object object) {

        // Check if the object is a string and convert it to a Path
        if (object instanceof String) {
            Path path = Paths.get((String) object);
            return getEncodingFormat(path, null)
        } else {
            return null
        }
    }


    /**
     * Get the encodingFormat of a file as MIME Type.
     * A file can exist at two places. At the source where Nextflow or the user stored the file,
     * or in the RO-Crate (i.e. target) location. The method takes both locations as arguments, if one
     * of the locations does not exist any more.
     *
     * @param source Path to file
     * @param target Path to file
     * @return the MIME type of the file or null, if it's not a file.
     */
    static def String getEncodingFormat(Path source, Path target) {
        String mime = null

        if(source && source.exists() && source.isFile())
            mime = Files.probeContentType(source) ?: null
        else if(target && target.exists() && target.isFile())
            mime = Files.probeContentType(target) ?: null
        else {
            return mime
        }

        // It seems that YAML has a media type only since beginning of 2024
        // Set this by hand if this is run on older systems:
        // https://httptoolkit.com/blog/yaml-media-type-rfc/
         if(!mime) {
             String extension = null
             if(source)
                 extension = FilenameUtils.getExtension(source.toString())
             else if(target)
                 extension = FilenameUtils.getExtension(target.toString())

             if(["yml", "yaml"].contains(extension))
                mime = "application/yaml"
         }

        return mime
    }
}
