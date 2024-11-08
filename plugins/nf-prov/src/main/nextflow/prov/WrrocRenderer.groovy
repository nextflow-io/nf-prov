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

import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import nextflow.Session
import nextflow.processor.TaskRun

import groovy.transform.CompileStatic
import nextflow.processor.TaskProcessor
import nextflow.script.ScriptBinding.ParamsMap
import nextflow.script.WorkflowMetadata

import groovy.transform.CompileStatic
import nextflow.processor.TaskProcessor
import nextflow.script.WorkflowMetadata
import org.yaml.snakeyaml.Yaml

/**
 * Renderer for the Provenance Run RO Crate format.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 * @author Felix Bartusch <felix.bartusch@uni-tuebingen.de>
 * @author Famke Bäuerle <famke.baeuerle@uni-tuebingen.de>
 */
@CompileStatic
class WrrocRenderer implements Renderer {

    private Path path
    private Path crateRootDir
    private Path workdir
    private Path projectDir

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
                    println "Failed to copy $source to $dest: ${e.message}"
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
                    println "Failed to copy $source to $dest: ${e.message}"
                }
            }
        }

        // get workflow config and store it in crate
        Path configFilePath = crateRootDir.resolve("nextflow.config")
        FileWriter configFileWriter = new FileWriter(configFilePath.toString())
        configMap.toConfigObject().writeTo(configFileWriter)

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
        // agent information
        final LinkedHashMap agent = new LinkedHashMap()
        if (wrrocParams.containsKey("agent")) {
            Map agentMap = wrrocParams["agent"] as Map
            if (agentMap.containsKey("orcid")) {
                agent.put("@id", agentMap.get("orcid"))
            } else {
                agent.put("@id", "agent-1")
            }
            agent.put("@type", "Person")
            if (agentMap.containsKey("name")) {
                agent.put("name", agentMap.get("name"))
            }
        }

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
                    "description"   : "",
                    // TODO: apply only if type is Path
                    // "encodingFormat": "text/plain",
                    // TODO: match to output if type is Path
                    // "workExample": ["@id": outputId],
                    "name"          : name,
                    // "valueRequired": "True"
                ]
            }

        final inputFiles = workflowInputMapping
            .collect { source, target ->
                [
                    "@id"           : crateRootDir.relativize(target).toString(),
                    "@type"         : "File",
                    "name"          : target.name,
                    "description"   : "",
                    "encodingFormat": Files.probeContentType(source) ?: "",
                    // TODO: apply if matching param is found
                    // "exampleOfWork": ["@id": paramId]
                ]
            }

        final outputFiles = workflowOutputs
            .collect { source, target ->
                [
                    "@id"           : crateRootDir.relativize(target).toString(),
                    "@type"         : "File",
                    "name"          : target.name,
                    "description"   : "",
                    "encodingFormat": Files.probeContentType(target) ?: "",
                    // TODO: create FormalParameter for each output file?
                    // "exampleOfWork": {"@id": "#reversed"}
                ]
            }

        // Combine both, inputFiles and outputFiles into one list. Remove duplicates that occur when an intermediate
        // file is output of a task and input of another task.
        Map<String, LinkedHashMap<String, String>> combinedInputOutputMap = [:]

        inputFiles.each { entry ->
            combinedInputOutputMap[entry['@id']] = entry
        }
        // Overwriting if 'id' already exists
        outputFiles.each { entry ->
            combinedInputOutputMap[entry['@id']] = entry
        }
        List<LinkedHashMap<String, String>> uniqueInputOutputFiles = combinedInputOutputMap.values().toList()

        final propertyValues = params
            .collect { name, value ->
                [
                    "@id"          : "#${name}-pv",
                    "@type"        : "PropertyValue",
                    "exampleOfWork": ["@id": "#${name}"],
                    "name"         : name,
                    "value"        : value
                    // TODO: catch genomes-pv case
                    // Idea: copy igenomes.config to the results directory and point the crate entry to that file
                    // describe it as extra nextflow.config file
                ]
            }

        // Maps used for finding tasks/CreateActions corresponding to a Nextflow process
        Map<String, List> processToTasks = [:].withDefault { [] }

        def createActions = tasks
            .collect { task ->

                List<String> resultFileIDs = []
                for (taskOutputParam in task.getOutputsByType(FileOutParam)) {
                    for (taskOutputFile in taskOutputParam.getValue()) {
                        // Path to file in workdir
                        Path taskOutputFilePath = Path.of(taskOutputFile.toString())

                        if (workflowOutputs.containsKey(taskOutputFilePath)) {
                            resultFileIDs.add(crateRootDir.relativize(workflowOutputs.get(taskOutputFilePath)).toString())
                        } else {
                            System.out.println("taskOutput not contained in workflowOutputs list: " + taskOutputFilePath)
                        }
                    }
                }

                List<String> objectFileIDs = []
                for (taskInputParam in task.getInputsByType(FileInParam)) {
                    for (taskInputFileHolder in taskInputParam.getValue()) {
                        FileHolder holder = (FileHolder) taskInputFileHolder
                        Path taskInputFilePath = holder.getStorePath()

                        if (workflowInputs.contains(taskInputFilePath)) {
                            // The mapping of input files to their path in the RO-Crate is only available for files we
                            // expect (e.g. files in workdir and pipeline assets). 
                            // TODO: Have to handle unexpected files ...
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
                    "result"      : resultFileIDs.collect(file -> ["@id": file]), // (file, ontology -> ["@id": file, "ontology": ontology])
                    "actionStatus": task.getExitStatus() == 0 ? "CompletedActionStatus" : "FailedActionStatus"
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
                // read in meta.yaml file (nf-core style)
                def metaYaml = readMetaYaml(process)
                // get ext properties from process
                def processorConfig = process.getConfig()
                def extProperties = processorConfig.ext as Map
                // use either ext property 'name' or 'name' from meta.yaml
                def toolNameTask = extProperties.containsKey('name') ? extProperties.get('name') as String : metaYaml.get('name')
                [
                    "@id"  : "#" + process.ownerScript.toString(),
                    "@type": "SoftwareApplication",
                    "name" : process.getName(),
                    "about" : [ "@id": toolNameTask ]
                    
                ]
            }

        final perTool = nextflowProcesses
            .collect() { process ->
                // read in meta.yaml file (nf-core style)
                def metaYaml = readMetaYaml(process)
                // get ext properties from process
                def processorConfig = process.getConfig()
                def extProperties = processorConfig.ext as Map
                // use either ext property 'name' or 'name' from meta.yaml
                def toolNameTask = extProperties.containsKey('name') ? extProperties.get('name') as String : metaYaml.get('name')
                
                def listOfToolMaps = new ArrayList()
                metaYaml.get('tools').each { tool -> listOfToolMaps.add( tool as Map ) }

                // get descriptions for all tools used in process
                // TODO: adapt so that multi-tool-processes get rendered seperately
                // TODO: extract more information from meta.yaml
                def softwareMap =  [:]
                def listOfDescriptions = new ArrayList()
                listOfToolMaps.each { toolMap ->
                    toolMap.each { field -> 
                        def fieldMap = field as Map
                        field.iterator().each {entry -> 
                            entry.iterator().each {entryField -> 
                                def entryFieldMap = entryField.getAt("value") as Map
                                listOfDescriptions.add(entryFieldMap.getAt("description"))
                                }
                            
                        }
                    }
                    softwareMap[toolNameTask] = listOfDescriptions
                }

                def createSoftwareFinal = [
                    "@id"         : toolNameTask,
                    "@type"       : "SoftwareApplication",
                    "description" : softwareMap.getAt(toolNameTask).toString()
                ]
                return createSoftwareFinal
            }

        final wfToolDescriptions = perTool.collect()

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
                    "label"     : process.getConfig().getLabels(),
                    "instrument": ["@id": "${metadata.projectName}#main/${process.getName()}"],
                    "name"      : "orchestrate " + "${metadata.projectName}#main/${process.getName()}",
                    "object"    : processToTasks[process.getId().toString()].collect({ taskID ->
                        ["@id": taskID]
                    })
                ]
            }

        final configFile =
            [
                "@id"        : "nextflow.config",
                "@type"      : "File",
                "name"       : "Effective Nextflow configuration",
                "description": "This is the effective configuration during runtime compiled from all configuration sources. "
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
                    //TODO Add publisher
                    //"publisher"  : "",
                    "datePublished": getDatePublished(),
                    "conformsTo" : [
                        ["@id": "https://w3id.org/ro/wfrun/process/0.1"],
                        ["@id": "https://w3id.org/ro/wfrun/workflow/0.1"],
                        ["@id": "https://w3id.org/ro/wfrun/provenance/0.1"],
                        ["@id": "https://w3id.org/workflowhub/workflow-ro-crate/1.0"]
                    ],
                    "name"       : "Workflow run of ${metadata.projectName}",
                    "description": manifest.description ?: "",
                    "hasPart"    : [
                        ["@id": metadata.projectName],
                        ["@id": "nextflow.config"],
                        *uniqueInputOutputFiles.collect(file -> ["@id": file["@id"]])
                    ],
                    "mainEntity" : ["@id": metadata.projectName],
                    "mentions"   : [
                        ["@id": "#${session.uniqueId}"],
                        *createActions.collect(createAction -> ["@id": createAction["@id"]])
                    ],
                    "license"    : licenseURLvalid ? ["@id": licenseURL.toString()] : licenseString
                ],
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
                    "name"               : metadata.projectName,
                    "programmingLanguage": ["@id": "https://w3id.org/workflowhub/workflow-ro-crate#nextflow"],
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
                ],
                [
                    "@id"       : "https://w3id.org/workflowhub/workflow-ro-crate#nextflow",
                    "@type"     : "ComputerLanguage",
                    "name"      : "Nextflow",
                    "identifier": "https://www.nextflow.io/",
                    "url"       : "https://www.nextflow.io/",
                    "version"   : nextflowVersion
                ],
                *wfSofwareApplications,
                *wfToolDescriptions,
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
                *controlActions,
                *createActions,
                configFile,
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
        Map<Path, Path> intermediateInputFiles = [:]

        tasks.collect { task ->
            for (taskOutputParam in task.getOutputsByType(FileOutParam)) {
                for (taskOutputFile in taskOutputParam.getValue()) {
                    // Path to file in workdir
                    Path taskOutputFilePath = Path.of(taskOutputFile.toString())

                    if (! workflowOutputs.containsKey(taskOutputFilePath)) {

                        // Find the relative path from workdir
                        Path relativePath = workdir.relativize(taskOutputFilePath)

                        // Build the new path by combining crateRootDir and the relative part
                        Path outputFileInCrate = crateRootDir.resolve(workdir.fileName).resolve(relativePath)

                        intermediateInputFiles.put(taskOutputFilePath, outputFileInCrate)
                    }
                }
            }
        }

        return intermediateInputFiles
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
            else {
                System.out.println("Unknown parentDir: " + inputPath.toString())
            }

            // Ignore file with unkown (e.g. null) parentDir
            if(parentDir) {
                Path relativePath = parentDir.relativize(inputPath)
                Path outputFileInCrate = crateRootDir.resolve(parentDir.fileName).resolve(relativePath)
                workflowInputMapping.put(inputPath, outputFileInCrate)
            }
        }

        return workflowInputMapping
    }

    /**
     * Read meta.yaml (nf-core style) file for a given Nextflow process.
     *
     * @param   TaskProcessor processor Nextflow process
     * @return  Yaml as Map
     */
    static Map readMetaYaml(TaskProcessor processor) {
        WorkflowMetadata workflow = processor.getOwnerScript()?.getBinding()?.getVariable('workflow') as WorkflowMetadata
        String projectDir = workflow?.getProjectDir()?.toString()

        // TODO: adapt this function to work with non-nf-core yaml files
        if (projectDir) {
            String moduleName = processor.getName()

            // Split the module name to get the tool name (last part)
            String[] moduleNameParts = moduleName.split(':')
            String toolName = moduleNameParts.length > 0 ? moduleNameParts[-1].toLowerCase() : moduleName.toLowerCase()

            // Construct the path to the meta.yml file
            Path metaFile = Paths.get(projectDir, 'modules', 'nf-core', toolName, 'meta.yml')

            if (Files.exists(metaFile)) {
                Yaml yaml = new Yaml()
                return yaml.load(metaFile.text) as Map
                }
            }
        return null
        }

    static String getDatePublished() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_DATE)
    }
}