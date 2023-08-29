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
import java.nio.file.Path
import java.nio.file.PathMatcher

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.trace.TraceObserver
import nextflow.trace.TraceRecord
import nextflow.file.FileHelper
import nextflow.processor.TaskHandler
import nextflow.processor.TaskRun
import nextflow.exception.AbortOperationException

/**
 * Plugin observer of workflow events
 *
 * @author Bruno Grande <bruno.grande@sagebase.org>
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@Slf4j
@CompileStatic
class ProvObserver implements TraceObserver {

    public static final String DEF_FILE_NAME = 'manifest.json'

    public static final List<String> VALID_FORMATS = ['legacy', 'bco']

    private Session session

    private Path path

    private Renderer renderer

    private Boolean overwrite

    private List<PathMatcher> matchers

    private Set<TaskRun> tasks = []

    private Map<Path,Path> publishedOutputs = [:]

    ProvObserver(Path path, String format, Boolean overwrite, List patterns) {
        this.path = path
        this.renderer = createRenderer(format)
        this.overwrite = overwrite
        this.matchers = patterns.collect { pattern ->
            FileSystems.getDefault().getPathMatcher("glob:**/${pattern}")
        }
    }

    private Renderer createRenderer(String format) {
        if( format == 'legacy' )
            return new LegacyRenderer()

        if( format == 'bco' )
            return new BcoRenderer()

        throw new IllegalArgumentException("Invalid provenance format -- valid formats are ${VALID_FORMATS.join(', ')}")
    }

    @Override
    void onFlowCreate(Session session) {
        this.session = session

        // check file existance
        final attrs = FileHelper.readAttributes(path)
        if( attrs ) {
            if( attrs.isDirectory() )
                log.warn "Provenance output directory already exists: ${path.toUriString()}"
            else if( overwrite && !path.delete() )
                throw new AbortOperationException("Unable to overwrite existing provenance manifest: ${path.toUriString()}")
            else if( !overwrite )
                throw new AbortOperationException("Provenance manifest already exists: ${path.toUriString()}")
        }
    }

    @Override
    void onProcessComplete(TaskHandler handler, TraceRecord trace) {
        // skip failed tasks
        final task = handler.task
        if( !task.isSuccess() )
            return

        tasks << task
    }

    @Override
    void onProcessCached(TaskHandler handler, TraceRecord trace) {
        tasks << handler.task
    }

    @Override
    void onFilePublish(Path destination, Path source) {
        boolean match = matchers.isEmpty() || matchers.any { matcher ->
            matcher.matches(destination)
        }

        if( !match )
            return

        publishedOutputs[source] = destination
    }

    @Override
    void onFlowComplete() {
        if( !session.isSuccess() )
            return

        renderer.render(session, tasks, publishedOutputs, path)
    }

}
