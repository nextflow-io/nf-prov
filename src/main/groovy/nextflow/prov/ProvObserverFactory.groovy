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
import nextflow.Session
import nextflow.trace.TraceObserverV2
import nextflow.trace.TraceObserverFactoryV2

/**
 * Factory for the plugin observer
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@Slf4j
@CompileStatic
class ProvObserverFactory implements TraceObserverFactoryV2 {

    @Override
    Collection<TraceObserverV2> create(Session session) {
        final observer = createProvObserver(session)
        return observer ? [ observer ] : []
    }

    protected TraceObserverV2 createProvObserver(Session session) {
        final opts = session.config.prov as Map ?: Collections.emptyMap()
        final config = new ProvConfig(opts)
        if( !config.enabled )
            return null

        if( !config.formats ) {
            log.warn "Config setting `prov.formats` is not defined -- no provenance reports will be produced"
            return null
        }

        return new ProvObserver(config.formats, config.patterns)
    }
}
