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

import nextflow.config.schema.ConfigOption
import nextflow.config.schema.ConfigScope
import nextflow.config.schema.ScopeName
import nextflow.script.dsl.Description

@ScopeName('prov')
@Description('''
    The `prov` scope allows you to configure the `nf-prov` plugin.
''')
class ProvConfig implements ConfigScope {

    @ConfigOption
    @Description('Create the provenance report (default: `true` if plugin is loaded).')
    boolean enabled

    @ConfigOption
    @Description('Configuration scope for the desired output formats.')
    ProvFormatsConfig formats
}


class ProvFormatsConfig implements ConfigScope {

    @Description('Configuration scope for the BCO output format.')
    ProvBcoConfig bco

    @Description('Configuration scope for the DAG output format.')
    ProvDagConfig dag

    @Description('Configuration scope for the legacy output format.')
    ProvLegacyConfig legacy

    @Description('Configuration scope for the WRROC output format.')
    ProvWrrocConfig wrroc
}


class ProvBcoConfig implements ConfigScope {

    @ConfigOption
    @Description('''
        The file name of the BCO manifest.
        ''')
    String file

    @ConfigOption
    @Description('''
        When `true` overwrites any existing BCO manifest with the same name.
        ''')
    boolean overwrite
}


class ProvDagConfig implements ConfigScope {

    @ConfigOption
    @Description('''
        The file name of the DAG diagram.
        ''')
    String file

    @ConfigOption
    @Description('''
        When `true` overwrites any existing DAG diagram with the same name.
        ''')
    boolean overwrite
}


class ProvLegacyConfig implements ConfigScope {

    @ConfigOption
    @Description('''
        The file name of the legacy manifest.
        ''')
    String file

    @ConfigOption
    @Description('''
        When `true` overwrites any existing legacy manifest with the same name.
        ''')
    boolean overwrite
}


class ProvWrrocConfig implements ConfigScope {

    @ConfigOption
    @Description('''
        The file name of the Workflow Run RO-Crate.
        ''')
    String file

    @ConfigOption
    @Description('''
        When `true` overwrites any existing Workflow Run RO-Crate with the same name.
        ''')
    boolean overwrite
}
