/*
 * Copyright 2026, Seqera Labs
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

import nextflow.config.spec.SpecNode
import spock.lang.Specification
import spock.lang.Unroll

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ProvConfigTest extends Specification {

    @Unroll
    def 'should declare config option prov.#name' () {
        given:
        final spec = SpecNode.Scope.of(ProvConfig, '')

        expect:
        spec.getChild(name.tokenize('.')) instanceof SpecNode.Option

        where:
        name << [
            'formats.wrroc.license',
            'formats.wrroc.publisher',
            'formats.wrroc.agent.name',
            'formats.wrroc.agent.orcid',
            'formats.wrroc.agent.ror',
            'formats.wrroc.agent.email',
            'formats.wrroc.agent.phone',
            'formats.wrroc.agent.contactType',
            'formats.wrroc.organization.name',
            'formats.wrroc.organization.ror',
            'formats.wrroc.organization.email',
            'formats.wrroc.organization.phone',
            'formats.wrroc.organization.contactType',
        ]
    }

}
