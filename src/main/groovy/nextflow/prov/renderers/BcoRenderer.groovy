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

package nextflow.prov.renderers

import java.nio.file.Path
import java.time.format.DateTimeFormatter

import groovy.json.JsonGenerator
import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import nextflow.Session
import nextflow.SysEnv
import nextflow.config.Manifest
import nextflow.dag.DAG
import nextflow.processor.TaskProcessor
import nextflow.processor.TaskRun
import nextflow.prov.ProvBcoConfig
import nextflow.prov.Renderer
import nextflow.prov.util.ProvHelper
import nextflow.script.WorkflowMetadata
import nextflow.util.PathNormalizer

import static nextflow.config.Manifest.ContributionType

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

    private Path workDir

    BcoRenderer(ProvBcoConfig config) {
        path = (config.file as Path).complete()
        overwrite = config.overwrite

        ProvHelper.checkFileOverwrite(path, overwrite)
    }

    @Override
    void render(Session session, Set<TaskRun> tasks, Map<String,Path> workflowOutputs, Map<Path,Path> publishedFiles) {
        // get workflow inputs
        final taskLookup = ProvHelper.getTaskLookup(tasks)
        final workflowInputs = ProvHelper.getWorkflowInputs(tasks, taskLookup)

        // sort tasks by step number, then by name, so that the order is independent of execution
        final processSteps = getProcessSteps(session.dag)
        final sortedTasks = tasks.toSorted { TaskRun a, TaskRun b ->
            processSteps[a.processor] <=> processSteps[b.processor] ?: a.name <=> b.name ?: a.hash.toString() <=> b.hash.toString()
        }

        // get workflow metadata
        final metadata = session.workflowMetadata
        this.normalizer = new PathNormalizer(metadata)
        this.workDir = metadata.workDir.normalize()

        final manifest = metadata.manifest
        final nextflowMeta = metadata.nextflow

        final dateCreated = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(metadata.start)
        final contributors = getContributors(manifest)
        final nextflowVersion = nextflowMeta.version.toString()
        final params = session.config.params as Map

        final config = session.config
        final review                  = config.navigate('prov.formats.bco.provenance_domain.review', []) as List<Map<String,?>>
        final derived_from            = config.navigate('prov.formats.bco.provenance_domain.derived_from') as String
        final obsolete_after          = config.navigate('prov.formats.bco.provenance_domain.obsolete_after') as String
        final embargo                 = config.navigate('prov.formats.bco.provenance_domain.embargo') as Map<String,String>
        final usability               = config.navigate('prov.formats.bco.usability_domain', []) as List<String>
        final keywords                = config.navigate('prov.formats.bco.description_domain.keywords', []) as List<String>
        final xref                    = config.navigate('prov.formats.bco.description_domain.xref', []) as List<Map<String,?>>
        final external_data_endpoints = config.navigate('prov.formats.bco.execution_domain.external_data_endpoints', []) as List<Map<String,String>>
        final environment_variables   = config.navigate('prov.formats.bco.execution_domain.environment_variables', []) as List<String>

        // create BCO manifest
        final bco = [
            "object_id": null,
            "spec_version": null,
            "etag": null,
            "provenance_domain": [
                "name": manifest.name ?: "",
                "version": manifest.version ?: "",
                "review": review,
                "derived_from": derived_from,
                "obsolete_after": obsolete_after,
                "embargo": embargo,
                "created": dateCreated,
                "modified": dateCreated,
                "contributors": contributors,
                "license": manifest.license ?: ""
            ],
            "usability_domain": usability,
            "extension_domain": [],
            "description_domain": [
                "keywords": keywords,
                "xref": xref,
                "platform": ["Nextflow"],
                "pipeline_steps": sortedTasks.collect { task -> [
                    "step_number": processSteps[task.processor] ?: 0,
                    "name": task.hash.toString(),
                    "description": task.name,
                    "input_list": ProvHelper.getTaskInputs(task).collect { name, source -> [
                        "uri": normalizeUri(source)
                    ] },
                    "output_list": ProvHelper.getTaskOutputs(task).collect { source -> [
                        "uri": normalizeUri(source)
                    ] }
                ] },
            ],
            "execution_domain": [
                "script": [
                    [
                        "uri": [
                            "uri": normalizePath(metadata.scriptFile)
                        ]
                    ]
                ],
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
                "external_data_endpoints": external_data_endpoints,
                "environment_variables": environment_variables.inject([:]) { acc, name ->
                    if( SysEnv.containsKey(name) )
                        acc.put(name, SysEnv.get(name))
                    acc
                }
            ],
            "parametric_domain": params.toConfigObject().flatten().collect( (k, v) -> [
                "param": k,
                "value": normalizePath(v.toString()),
                "step": ""
            ] ),
            "io_domain": [
                "input_subdomain": workflowInputs.collect { source -> normalizeUri(source) }.sort().collect { uri -> [
                    "uri": [
                        "uri": uri
                    ]
                ] },
                "output_subdomain": publishedFiles.entrySet().sort { e -> e.value.toUriString() }.collect { e -> [
                    "mediatype": ProvHelper.getEncodingFormat(e.key) ?: "",
                    "uri": [
                        "filename": normalizePath(e.key),
                        "uri": normalizePath(e.value)
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

        // omit null fields since the BCO schema does not allow them
        final generator = new JsonGenerator.Options().excludeNulls().build()

        // compute etag as SHA-256 of the canonical JSON (sorted keys, no whitespace),
        // excluding object_id, spec_version, and etag
        final etag = generator.toJson(sortKeys(bco)).digest('SHA-256')

        bco.object_id = "urn:uuid:${UUID.randomUUID()}"
        bco.spec_version = "https://w3id.org/ieee/ieee-2791-schema/2791object.json"
        bco.etag = etag

        // render BCO manifest to JSON file
        path.text = JsonOutput.prettyPrint(generator.toJson(bco))
    }

    private static Object sortKeys(Object value) {
        if( value instanceof Map )
            return new TreeMap((value as Map).collectEntries { k, v -> [k.toString(), sortKeys(v)] })
        if( value instanceof Collection )
            return (value as Collection).collect { v -> sortKeys(v) }
        return value
    }

    /**
     * Get the step number of each process, defined as the number of
     * processes on the longest path to the process in the workflow DAG.
     * Parallel processes have the same step number.
     *
     * @param dag
     */
    private static Map<TaskProcessor,Integer> getProcessSteps(DAG dag) {
        final Map<DAG.Vertex,List<DAG.Vertex>> preds = [:]
        for( final edge : dag.edges ) {
            if( edge.from != null && edge.to != null )
                preds.computeIfAbsent(edge.to, (k) -> new ArrayList<DAG.Vertex>()).add(edge.from)
        }

        final Map<DAG.Vertex,Integer> depths = [:]
        final Map<TaskProcessor,Integer> result = [:]
        for( final vertex : dag.vertices ) {
            if( vertex.process != null )
                result[vertex.process] = getDepth(vertex, preds, depths)
        }
        return result
    }

    private static int getDepth(DAG.Vertex vertex, Map<DAG.Vertex,List<DAG.Vertex>> preds, Map<DAG.Vertex,Integer> depths) {
        if( depths.containsKey(vertex) )
            return depths[vertex]
        // placeholder to break cycles (e.g. process recursion)
        depths[vertex] = 0
        int depth = 0
        for( final pred : preds.getOrDefault(vertex, []) )
            depth = Math.max(depth, getDepth(pred, preds, depths))
        if( vertex.process != null )
            depth += 1
        depths[vertex] = depth
        return depth
    }

    /**
     * Normalize a path as an absolute URI, since the BCO schema
     * does not allow relative work directory paths.
     *
     * @param path
     */
    private String normalizeUri(Path path) {
        if( !path.normalize().startsWith(workDir) )
            return normalizePath(path)
        final uri = path.toUriString()
        return uri.startsWith('/') ? 'file://' + uri : uri
    }

    private List getContributors(Manifest manifest) {
        (manifest.contributors ?: []).collect { c -> [
            "name": c.name,
            "affiliation": c.affiliation,
            "email": c.email,
            "contribution": c.contribution.collect { ct -> CONTRIBUTION_TYPES[ct] },
            "orcid": c.orcid
        ] }
    }

    private static Map<ContributionType, String> CONTRIBUTION_TYPES = [
        (ContributionType.AUTHOR) : "authoredBy",
        (ContributionType.MAINTAINER) : "curatedBy",
        (ContributionType.CONTRIBUTOR) : "curatedBy",
    ]

}
