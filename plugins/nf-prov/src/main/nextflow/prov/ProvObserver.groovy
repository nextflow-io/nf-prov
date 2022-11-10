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
import nextflow.trace.TraceHelper
import nextflow.file.FileHelper
import nextflow.exception.AbortOperationException

/**
 * Plugin observer of workflow events
 *
 * @author Bruno Grande <bruno.grande@sagebase.org>
 */
@Slf4j
@CompileStatic
class ProvObserver implements TraceObserver {

    public static final String DEF_FILE_NAME = 'manifest.json'

    private Session session

    private Map config

    private boolean enabled

    private Path path

    private List<PathMatcher> matchers

    private List<Map> publishedPaths

    @Override
    void onFlowCreate(Session session) {
        this.session = session
        this.config = session.config
        this.enabled = this.config.navigate('prov.enabled', true)
        this.config.overwrite = this.config.navigate('prov.overwrite', false)
        this.config.patterns = this.config.navigate('prov.patterns', [])
        this.config.file = this.config.navigate('prov.file', DEF_FILE_NAME)
        this.path = (this.config.file as Path).complete()

        // check file existance
        final attrs = FileHelper.readAttributes(this.path)
        if( this.enabled && attrs ) {
            if( this.config.overwrite && (attrs.isDirectory() || !this.path.delete()) )
                throw new AbortOperationException("Unable to overwrite existing file manifest: ${this.path.toUriString()}")
            else if( !this.config.overwrite )
                throw new AbortOperationException("File manifest already exists: ${this.path.toUriString()}")
        }

        this.matchers = this.config.patterns.collect { pattern ->
            FileSystems.getDefault().getPathMatcher("glob:**/${pattern}")
        }

        this.publishedPaths = new ArrayList<>()
    }

    @Override
    void onFilePublish(Path destination, Path source) {
        boolean match = this.matchers.isEmpty() || this.matchers.any { matcher ->
            matcher.matches(destination)
        }

        def pathMap = [
            'source': source.toUriString(),
            'target': destination.toUriString()
        ]

        if ( match ) {
            this.publishedPaths.add(pathMap)
        }
    }

    @Override
    void onFlowComplete() {
        // make sure there are files to publish
        if ( !this.enabled || this.publishedPaths.isEmpty() ) {
            return
        }

        // generate manifest map
        def manifest = [ "published": [] ]
        this.publishedPaths.each { path ->
            manifest.published.add(path)
        }

        // output manifest map as JSON
        def manifest_json = JsonOutput.toJson(manifest)
        def manifest_json_pretty = JsonOutput.prettyPrint(manifest_json)

        // create JSON file manifest
        Path publishedPathsFile = Files.createFile(this.path)
        publishedPathsFile << "${manifest_json_pretty}\n"

    }

}
