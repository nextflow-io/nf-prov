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

import java.nio.file.Path

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.exception.AbortOperationException
import nextflow.trace.TraceObserver
import nextflow.trace.TraceObserverFactory

/**
 * Factory for the plugin observer
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@Slf4j
@CompileStatic
class ProvObserverFactory implements TraceObserverFactory {

    @Override
    Collection<TraceObserver> create(Session session) {
        [ createProvObserver(session.config) ]
    }

    protected TraceObserver createProvObserver(Map config) {
        final enabled = config.navigate('prov.enabled', true) as Boolean
        if( !enabled )
            return

        final format = config.navigate('prov.format') as String
        final file = config.navigate('prov.file', 'manifest.json') as String
        final overwrite = config.navigate('prov.overwrite') as Boolean
        def formats = [:]
        if( format ) {
            log.warn "Config options `prov.format`, `prov.file`, and `prov.overwrite` are deprecated -- use `prov.formats` instead"
            formats[format] = [file: file, overwrite: overwrite]
        }

        formats = config.navigate('prov.formats', formats) as Map

        if( !formats )
            throw new AbortOperationException("Config setting `prov.formats` is required to specify provenance output formats")

        final patterns = config.navigate('prov.patterns', []) as List<String>
        new ProvObserver(formats, patterns)
    }
}
