/*
 * Copyright 2022, Seqera Labs
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

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.config.spec.ConfigOption
import nextflow.config.spec.ConfigScope
import nextflow.config.spec.ScopeName
import nextflow.script.dsl.Description

@ScopeName('prov')
@Description('''
    The `prov` scope allows you to configure the `nf-prov` plugin.
''')
@CompileStatic
class ProvConfig implements ConfigScope {

    @ConfigOption
    @Description('''
        Create the provenance report (default: `true` if plugin is loaded).
    ''')
    final boolean enabled

    @ConfigOption
    @Description('''
        The directory where the provenance reports should be saved. The path is resolved against the workflow output directory (`outputDir`). It must be a relative path within the output directory, and it cannot be used when a report file is an absolute path. By default, it is not used and report files are resolved against the launch directory.
    ''')
    final String directory

    @Description('''
        Configuration scope for the desired output formats.
    ''')
    final ProvFormatsConfig formats

    @Description('''
        List of file patterns to include in the provenance report, from the set of published files. By default, all published files are included.
    ''')
    final List<String> patterns

    /* required by extension point -- do not remove */
    ProvConfig() {}

    ProvConfig(Map opts) {
        enabled = opts.enabled != null ? opts.enabled as boolean : true
        directory = opts.directory
        formats = opts.formats ? new ProvFormatsConfig(opts.formats as Map) : null
        patterns = opts.patterns as List<String> ?: []
    }
}


@Slf4j
@CompileStatic
class ProvFormatsConfig implements ConfigScope {

    @Description('''
        Configuration scope for the BCO output format.
    ''')
    final ProvBcoConfig bco

    @Description('''
        Configuration scope for the DAG output format.
    ''')
    final ProvDagConfig dag

    @Description('''
        Configuration scope for the GEXF output format.
    ''')
    final ProvGexfConfig gexf

    @Description('''
        Configuration scope for the WRROC output format.
    ''')
    final ProvWrrocConfig wrroc

    ProvFormatsConfig(Map opts) {
        bco = opts.bco ? new ProvBcoConfig(opts.bco as Map) : null
        dag = opts.dag ? new ProvDagConfig(opts.dag as Map) : null
        gexf = opts.gexf ? new ProvGexfConfig(opts.gexf as Map) : null
        wrroc = opts.wrroc ? new ProvWrrocConfig(opts.wrroc as Map) : null

        if( opts.legacy )
            log.warn "The `legacy` provenance format is no longer supported"
    }
}


@CompileStatic
class ProvBcoConfig implements ConfigScope {

    @ConfigOption
    @Description('''
        The file name of the BCO manifest.
    ''')
    final String file

    @ConfigOption
    @Description('''
        When `true` overwrites any existing BCO manifest with the same name (default: `false`).
    ''')
    final boolean overwrite

    ProvBcoConfig(Map opts) {
        file = opts.file
        overwrite = opts.overwrite as boolean
    }
}


@CompileStatic
class ProvDagConfig implements ConfigScope {

    @ConfigOption
    @Description('''
        The file name of the DAG diagram.
    ''')
    String file

    @ConfigOption
    @Description('''
        When `true` overwrites any existing DAG diagram with the same name (default: `false`).
    ''')
    boolean overwrite

    ProvDagConfig(Map opts) {
        file = opts.file
        overwrite = opts.overwrite as boolean
    }
}


@CompileStatic
class ProvGexfConfig implements ConfigScope {

    @ConfigOption
    @Description('''
        The file name of the GEXF file.
    ''')
    String file

    @ConfigOption
    @Description('''
        When `true` overwrites any existing GEXF file with the same name (default: `false`).
    ''')
    boolean overwrite

    ProvGexfConfig(Map opts) {
        file = opts.file
        overwrite = opts.overwrite as boolean
    }
}


@CompileStatic
class ProvWrrocConfig implements ConfigScope {

    @ConfigOption
    @Description('''
        The file name of the Workflow Run RO-Crate.
    ''')
    final String file

    @ConfigOption
    @Description('''
        When `true` overwrites any existing Workflow Run RO-Crate with the same name (default: `false`).
    ''')
    final boolean overwrite

    @ConfigOption
    @Description('''
        The license for the Workflow Run RO-Crate.
    ''')
    final String license

    @Description('''
        The agent (person) running the workflow.
    ''')
    final ProvWrrocContactConfig agent

    @Description('''
        The organization of the agent running the workflow.
    ''')
    final ProvWrrocContactConfig organization

    @ConfigOption
    @Description('''
        The publisher of the Workflow Run RO-Crate. Must be the ORCID of the agent or the ROR of the organization.
    ''')
    final String publisher

    ProvWrrocConfig(Map opts) {
        file = opts.file
        overwrite = opts.overwrite as boolean
        license = opts.license
        agent = opts.agent ? new ProvWrrocContactConfig(opts.agent as Map) : null
        organization = opts.organization ? new ProvWrrocContactConfig(opts.organization as Map) : null
        publisher = opts.publisher
    }
}

@CompileStatic
class ProvWrrocContactConfig implements ConfigScope {

    @ConfigOption
    @Description('''
        The name of the person or organization.
    ''')
    final String name

    @ConfigOption
    @Description('''
        The ORCID of the person.
    ''')
    final String orcid

    @ConfigOption
    @Description('''
        The ROR of the organization.
    ''')
    final String ror

    @ConfigOption
    @Description('''
        The contact email address.
    ''')
    final String email

    @ConfigOption
    @Description('''
        The contact phone number.
    ''')
    final String phone

    @ConfigOption
    @Description('''
        The contact type (e.g. `Researcher`).
    ''')
    final String contactType

    ProvWrrocContactConfig(Map opts) {
        name = opts.name
        orcid = opts.orcid
        ror = opts.ror
        email = opts.email
        phone = opts.phone
        contactType = opts.contactType
    }
}
