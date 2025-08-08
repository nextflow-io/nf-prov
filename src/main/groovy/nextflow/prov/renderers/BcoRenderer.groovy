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

import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import nextflow.Session
import nextflow.SysEnv
import nextflow.config.Manifest
import nextflow.processor.TaskRun
import nextflow.prov.ProvBcoConfig
import nextflow.prov.Renderer
import nextflow.prov.util.ProvHelper
import nextflow.script.WorkflowMetadata
import nextflow.util.CacheHelper
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

        // get workflow metadata
        final metadata = session.workflowMetadata
        this.normalizer = new PathNormalizer(metadata)

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
                "license": manifest.license
            ],
            "usability_domain": usability,
            "extension_domain": [],
            "description_domain": [
                "keywords": keywords,
                "xref": xref,
                "platform": ["Nextflow"],
                "pipeline_steps": tasks.sort( (task) -> task.id ).collect { task -> [
                    "step_number": task.id,
                    "name": task.hash.toString(),
                    "description": task.name,
                    "input_list": task.getInputFilesMap().collect { name, source -> [
                        "uri": normalizePath(source)
                    ] },
                    "output_list": ProvHelper.getTaskOutputs(task).collect { source -> [
                        "uri": normalizePath(source)
                    ] }
                ] },
            ],
            "execution_domain": [
                "script": [ normalizePath(metadata.scriptFile) ],
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
                "step": "0"
            ] ),
            "io_domain": [
                "input_subdomain": workflowInputs.collect { source -> [
                    "uri": [
                        "uri": normalizePath(source)
                    ]
                ] },
                "output_subdomain": publishedFiles.collect { source, target -> [
                    "mediatype": ProvHelper.getEncodingFormat(source) ?: "",
                    "uri": [
                        "filename": normalizePath(source),
                        "uri": normalizePath(target)
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

        // compute etag
        // TODO: make a more canonical hash
        final etag = CacheHelper.hasher(bco, CacheHelper.HashMode.SHA256).hash()

        // append non-cacheable fields
        bco.object_id = "urn:uuid:${UUID.randomUUID()}"
        bco.spec_version = "https://w3id.org/ieee/ieee-2791-schema/2791object.json"
        bco.etag = etag.toString()

        // render BCO manifest to JSON file
        path.text = JsonOutput.prettyPrint(JsonOutput.toJson(bco))
    }

    private List getContributors(Manifest manifest) {
        manifest.contributors.collect { c -> [
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
