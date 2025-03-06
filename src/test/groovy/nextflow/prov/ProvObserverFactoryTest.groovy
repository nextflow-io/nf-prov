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

import nextflow.Session
import spock.lang.Specification

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ProvObserverFactoryTest extends Specification {

    def 'should return observer' () {
        when:
        def config = [
            prov: [
                formats: [
                    legacy: [
                        file: 'manifest.json'
                    ]
                ]
            ]
        ]
        def session = Spy(Session) {
            getConfig() >> config
        }
        def result = new ProvObserverFactory().create(session)
        then:
        result.size()==1
        result[0] instanceof ProvObserver
    }

}
