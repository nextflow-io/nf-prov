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

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher

import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.trace.TraceObserver

/**
 * Plugin observer of workflow events
 *
 * @author Bruno Grande <bruno.grande@sagebase.org>
 */
@Slf4j
@CompileStatic
class ProvObserver implements TraceObserver {

    public static final String DEF_FILE_NAME = 'manifest.txt'

    private Session session

    private Map config

    private Path path

    private List<PathMatcher> matchers

    private List<Path> publishedPaths

    @Override
    void onFlowCreate(Session session) {
        this.session = session
        this.config = session.config
        this.config.patterns = this.config.navigate('prov.patterns', [])
        this.config.file = this.config.navigate('prov.file', DEF_FILE_NAME)
        this.path = (this.config.file as Path).complete()

        this.matchers = this.config.patterns.collect { pattern ->
            FileSystems.getDefault().getPathMatcher("glob:**/${pattern}")
        }

        this.publishedPaths = new ArrayList<>()
    }

    @Override
    void onFilePublish(Path destination) {
        boolean match = this.matchers.isEmpty() || this.matchers.any { matcher ->
            matcher.matches(destination)
        }

        if ( match )
            this.publishedPaths << destination
    }

    @Override
    void onFlowComplete() {
        // make sure there are files to publish
        if( this.publishedPaths.isEmpty() ) {
            return
        }

        // save the list of paths to a temp file
        // TODO: Format list of published files as JSON
        Path publishedPathsFile = Files.createFile(this.path)

        this.publishedPaths.each { path ->
            publishedPathsFile << "${path.toUriString()}\n"
        }
    }

}
