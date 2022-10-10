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
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@Slf4j
@CompileStatic
class ProvObserver implements TraceObserver {

    private Session session

    private Map config

    private List<PathMatcher> matchers

    private List<Path> paths

    @Override
    void onFlowCreate(Session session) {
        this.session = session
        this.config = session.config.navigate('prov') as Map
        this.config.paths = this.config.paths ?: []

        this.matchers = this.config.paths.collect { pattern ->
            FileSystems.getDefault().getPathMatcher("glob:**/${pattern}")
        }

        this.paths = new ArrayList<>()
    }

    @Override
    void onFilePublish(Path destination) {
        boolean match = this.matchers.isEmpty() || this.matchers.any { matcher ->
            matcher.matches(destination)
        }

        if ( match )
            this.paths << destination
    }

    @Override
    void onFlowComplete() {
        // make sure prov is configured
        if( !config?.packageName ) {
            return
        }

        // make sure there are files to publish
        if( this.paths.isEmpty() ) {
            return
        }

        // save the list of paths to a temp file
        def pathsFile = Files.createTempFile('nxf-','.dot')

        this.paths.each { path ->
            pathsFile << "${path.toUriString()}\n"
        }

        // build the prov command
        def provCmd = "prov-cli push ${pathsFile} ${config.packageName}"

        if( config.registry )
            provCmd += " --registry ${config.registry}"

        if( config.message )
            provCmd += " --message \'${config.message}\'"

        if( config.meta ) {
            if( config.meta instanceof Map )
                provCmd += " --meta \'${JsonOutput.toJson(config.meta)}\'"
            else
                throw new IllegalStateException("Not a valid prov meta object: ${config.meta}")
        }

        if( config.force )
            provCmd += " --force"

        // run the prov command
        final cmd = "command -v prov-cli &>/dev/null || exit 128 && ${provCmd}"
        final process = new ProcessBuilder().command('bash','-c', cmd).redirectErrorStream(true).start()
        final exitStatus = process.waitFor()
        if( exitStatus == 128 ) {
            log.warn 'The `prov-cli` command is required to publish Prov packages -- See https://github.com/nextflow-io/nf-prov for more info.'
        }
        else if( exitStatus > 0 ) {
            log.debug "prov-cli error -- command `$cmd` -- exit status: $exitStatus\n${process.text?.indent()}"
            log.warn "Failed to publish Prov package"
        }
        else {
            log.trace "prov-cli trace -- command `$cmd`\n${process.text?.indent()}"
        }

        // cleanup
        Files.deleteIfExists(pathsFile)
    }
}
