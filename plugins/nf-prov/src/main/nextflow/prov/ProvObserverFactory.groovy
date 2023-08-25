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
import nextflow.Session
import nextflow.trace.TraceObserver
import nextflow.trace.TraceObserverFactory

/**
 * Factory for the plugin observer
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
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

        final file = config.navigate('prov.file', ProvObserver.DEF_FILE_NAME)
        final path = (file as Path).complete()
        final overwrite = config.navigate('prov.overwrite') as Boolean
        final patterns = config.navigate('prov.patterns', []) as List
        new ProvObserver(path, format, overwrite, patterns)
    }
}
