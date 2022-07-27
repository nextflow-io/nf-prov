/*
 * Copyright 2021, Seqera Labs
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

package nextflow.quilt

import java.nio.file.Path

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.trace.TraceObserver

/**
 * Plugin observer of workflow events
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@Slf4j
@CompileStatic
class QuiltObserver implements TraceObserver {

    private Session session

    private List<Path> paths

    @Override
    void onFlowCreate(Session session) {
        this.session = session
        this.paths = new ArrayList<>()
    }

    @Override
    void onFlowComplete() {
        log.info 'The following files were published:'

        this.paths.each { path ->
            log.info "  ${path}"
        }
    }

    @Override
    void onFilePublish(Path destination) {
        this.paths << destination
    }
}
