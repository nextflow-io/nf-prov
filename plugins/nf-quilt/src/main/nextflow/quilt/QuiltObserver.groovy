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

package nextflow.quilt

import java.nio.file.Files
import java.nio.file.Path

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
class QuiltObserver implements TraceObserver {

    private Session session

    private Map config

    private List<Path> paths

    @Override
    void onFlowCreate(Session session) {
        this.session = session
        this.config = session.config.navigate('quilt') as Map
        this.paths = new ArrayList<>()
    }

    @Override
    void onFlowComplete() {
        // make sure quilt is configured
        if( !config?.packageName ) {
            return
        }

        // save the list of paths to a temp file
        def pathsFile = Files.createTempFile('nxf-','.dot')

        this.paths.each { path ->
            pathsFile << "${path.toUriString()}\n"
        }

        // build the quilt command
        def quiltCmd = "quilt-api push ${pathsFile} ${config.packageName}"

        if( config.registry )
            quiltCmd += " --registry ${config.registry}"

        if( config.message )
            quiltCmd += " --message \'${config.message}\'"

        if( config.meta ) {
            if( config.meta instanceof Map )
                quiltCmd += " --meta \'${JsonOutput.toJson(config.meta)}\'"
            else
                throw new IllegalStateException("Not a valid quilt meta object: ${config.meta}")
        }

        if( config.force )
            quiltCmd += " --force"

        // run the quilt command
        final cmd = "command -v quilt-api &>/dev/null || exit 128 && ${quiltCmd}"
        final process = new ProcessBuilder().command('bash','-c', cmd).redirectErrorStream(true).start()
        final exitStatus = process.waitFor()
        if( exitStatus == 128 ) {
            log.warn 'The `quilt-api` command is required to publish Quilt packages -- See https://github.com/seqeralabs/quilt-api for more info.'
        }
        else if( exitStatus > 0 ) {
            log.debug "quilt-api error -- command `$cmd` -- exit status: $exitStatus\n${process.text?.indent()}"
            log.warn "Failed to publish Quilt package"
        }
        else {
            log.trace "quilt-api trace -- command `$cmd`\n${process.text?.indent()}"
        }

        // cleanup
        Files.deleteIfExists(pathsFile)
    }

    @Override
    void onFilePublish(Path destination) {
        this.paths << destination
    }
}
